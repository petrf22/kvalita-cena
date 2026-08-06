package cz.kvalitacena.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cz.kvalitacena.config.NominatimProperties;
import cz.kvalitacena.controller.GeocodeCandidate;
import cz.kvalitacena.controller.GeocodeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Geokódování adresy přes OpenStreetMap Nominatim — VÝHRADNĚ tady na serveru (docs/soukromi.md:
 * dotaz z mobilu/prohlížeče by prozradil Nominatimu přímo IP uživatele). Žádný import POI, jen
 * "adresa → souřadnice" (viz plán projektu). Výsledek se necachuje do core.* ani osm.* — do
 * core.store se dostane jen lat/lon a osm_ref VYBRANÉHO kandidáta, teprve když ho uživatel
 * potvrdí ve StoreService.create().
 *
 * <p>Usage policy Nominatimu (https://operations.osmfoundation.org/policies/nominatim/)
 * vyžaduje identifikovatelný User-Agent a nejvýš 1 dotaz/s — {@link #throttle()} to vynucuje
 * v celé appce najednou (jednoduchý sdílený čítač, ne fronta — pro objem MVP dost).
 *
 * <p>Výpadek/timeout NESMÍ shodit založení obchodu — každá chyba vede na prázdný seznam
 * kandidátů, nikdy na vyhozenou výjimku.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeocodingService {

  private final NominatimProperties nominatimProperties;

  // Líné vytvoření napřímo v konstruktoru by narazilo na pořadí Spring bean lifecycle —
  // @ConfigurationProperties se na @Component beanu binduje ve stejné fázi jako @PostConstruct
  // a pořadí mezi nimi není zaručené, takže by getTimeout()/getBaseUrl() v @PostConstruct mohly
  // vidět ještě nenabindované (null) hodnoty. Řešení: postavit RestClient/cache až při PRVNÍM
  // skutečném použití, kdy je bean prokazatelně plně inicializovaný.
  private volatile RestClient restClient;
  private volatile Cache<String, List<GeocodeCandidate>> cache;

  private final AtomicLong lastRequestAtMs = new AtomicLong(0);

  private synchronized RestClient restClient() {
    if (restClient == null) {
      HttpClient httpClient = HttpClient.newBuilder()
          .connectTimeout(nominatimProperties.getTimeout())
          .build();
      var requestFactory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
      requestFactory.setReadTimeout(nominatimProperties.getTimeout());
      restClient = RestClient.builder()
          .baseUrl(nominatimProperties.getBaseUrl())
          .requestFactory(requestFactory)
          .defaultHeader("User-Agent", nominatimProperties.getUserAgent())
          .build();
    }
    return restClient;
  }

  private synchronized Cache<String, List<GeocodeCandidate>> cache() {
    if (cache == null) {
      cache = Caffeine.newBuilder()
          .expireAfterWrite(nominatimProperties.getCacheTtl())
          .maximumSize(1000)
          .build();
    }
    return cache;
  }

  public GeocodeResult geocode(String street, String city, String postalCode, String country) {
    String key = String.join("|",
        n(street), n(city), n(postalCode), n(country));
    List<GeocodeCandidate> candidates = cache().get(key, k -> fetchFromNominatim(street, city, postalCode, country));
    return new GeocodeResult(candidates, nominatimProperties.getAttribution());
  }

  private List<GeocodeCandidate> fetchFromNominatim(String street, String city, String postalCode, String country) {
    try {
      throttle();
      NominatimResult[] results = restClient().get()
          .uri(uriBuilder -> {
            var builder = uriBuilder.path("/search")
                .queryParam("format", "json")
                .queryParam("addressdetails", 0)
                .queryParam("limit", 5)
                .queryParam("city", city);
            if (street != null && !street.isBlank()) builder.queryParam("street", street);
            if (postalCode != null && !postalCode.isBlank()) builder.queryParam("postalcode", postalCode);
            if (country != null && !country.isBlank()) builder.queryParam("country", country);
            return builder.build();
          })
          .retrieve()
          .body(NominatimResult[].class);

      if (results == null) return List.of();
      return java.util.Arrays.stream(results)
          .map(this::toCandidate)
          .filter(java.util.Objects::nonNull)
          .toList();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of();
    } catch (RuntimeException e) {
      log.warn("Geokódování adresy přes Nominatim selhalo, obchod se založí bez souřadnic: {}", e.getMessage());
      return List.of();
    }
  }

  /** Nejvýš 1 dotaz/s na Nominatim (usage policy) — sdílené napříč celou appkou. */
  private void throttle() throws InterruptedException {
    long minGapMs = 1000;
    long now = System.currentTimeMillis();
    long previous = lastRequestAtMs.getAndUpdate(prev -> Math.max(now, prev + minGapMs));
    long waitMs = previous + minGapMs - now;
    if (waitMs > 0) {
      Thread.sleep(waitMs);
    }
  }

  private GeocodeCandidate toCandidate(NominatimResult result) {
    try {
      double lat = Double.parseDouble(result.lat());
      double lon = Double.parseDouble(result.lon());
      String osmRef = result.osmType() != null && result.osmId() != null
          ? result.osmType() + "/" + result.osmId()
          : null;
      return new GeocodeCandidate(lat, lon, result.displayName(), osmRef);
    } catch (NumberFormatException | NullPointerException e) {
      return null;
    }
  }

  private String n(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
  }

  /** Tvar odpovědi Nominatim /search — viz https://nominatim.org/release-docs/latest/api/Search/. */
  private record NominatimResult(
      @JsonProperty("lat") String lat,
      @JsonProperty("lon") String lon,
      @JsonProperty("display_name") String displayName,
      @JsonProperty("osm_type") String osmType,
      @JsonProperty("osm_id") Long osmId) {
  }
}
