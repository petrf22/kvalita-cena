package cz.kvalitacena.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cz.kvalitacena.config.OverpassProperties;
import cz.kvalitacena.controller.OsmLookupStatus;
import cz.kvalitacena.controller.OsmStoreCandidate;
import cz.kvalitacena.controller.OsmStoreCandidateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provozovny v okolí z OpenStreetMap přes Overpass API — nabídka k založení obchodu, když ho
 * appka ve vlastním katalogu nezná (`core.store` se záměrně nepředvyplňuje, docs/nasazeni.md).
 * Dotaz jde VÝHRADNĚ ze serveru, nikdy z mobilu (docs/soukromi.md: jinak by šla Overpassu
 * přímo IP uživatele), a souřadnice do něj vcházejí už zaokrouhlené volajícím.
 *
 * <p>Stavbou je to dvojče {@link GeocodingService} (líný RestClient kvůli pořadí Spring bean
 * lifecycle, sdílený throttle, Caffeine cache, fail-soft) — s jedním podstatným rozdílem:
 * <b>neúspěch se NEcachuje</b>. GeocodingService prázdný výsledek z catch bloku uloží do cache
 * spolu s úspěchy, což je u geokódování jedné adresy neškodné; tady by hodinový výpadek
 * Overpassu zamrzl na celé TTL a uživatelé by mezitím zakládali duplicity. Selhání se proto
 * hlásí jako {@link OsmLookupStatus#UNAVAILABLE} a příští dotaz to zkusí znovu.
 *
 * <p>Z odpovědi se nikdy nic nekopíruje do `core.*` ani `off.*`/`osm.*` — do `core.store` se
 * dostane jen to, co člověk potvrdí ve formuláři, a nese značku `data_origin = 'OSM_DERIVED'`
 * (docs/datovy-model.md, "Oddělení schémat kvůli ODbL").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverpassPoiService {

  /**
   * Co se z OSM považuje za provozovnu. `shop=*` je běžný obchod; `amenity=marketplace` jsou
   * tržiště a farmářské trhy, tedy přesně "lokální dodavatelé", kvůli kterým appka vznikla
   * (kořenový CLAUDE.md). Bezejmenný POI se filtruje už v dotazu — bez názvu je pro
   * `core.store` nepoužitelný (STORE_NAME_REQUIRED, `uq_store_identity` stojí na názvu)
   * a jen by zvětšoval odpověď.
   *
   * <p>`nwr` (node+way+relation jedním výrazem), ne tři samostatné řádky na značku: měřeno
   * proti veřejné instanci je rozepsaná varianta násobně dražší a u zatížené instance spadne
   * na timeout, zatímco `nwr` projde. `out center` doplní `way`/`relation` těžiště, protože
   * appka potřebuje jeden bod, ne geometrii.
   */
  private static final String QUERY_TEMPLATE = """
      [out:json][timeout:%d];
      (
        nwr["shop"]["name"](around:%.1f,%.6f,%.6f);
        nwr["amenity"="marketplace"]["name"](around:%.1f,%.6f,%.6f);
      );
      out center tags %d;
      """;

  private final OverpassProperties overpassProperties;
  private final Messages messages;

  private volatile RestClient restClient;
  private volatile Cache<String, List<OsmStoreCandidate>> cache;

  private final AtomicLong lastRequestAtMs = new AtomicLong(0);

  private synchronized RestClient restClient() {
    if (restClient == null) {
      HttpClient httpClient = HttpClient.newBuilder()
          .connectTimeout(overpassProperties.getTimeout())
          .build();
      var requestFactory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
      requestFactory.setReadTimeout(overpassProperties.getTimeout());
      restClient = RestClient.builder()
          .baseUrl(overpassProperties.getBaseUrl())
          .requestFactory(requestFactory)
          .defaultHeader("User-Agent", overpassProperties.getUserAgent())
          .build();
    }
    return restClient;
  }

  private synchronized Cache<String, List<OsmStoreCandidate>> cache() {
    if (cache == null) {
      cache = Caffeine.newBuilder()
          .expireAfterWrite(overpassProperties.getCacheTtl())
          .maximumSize(1000)
          .build();
    }
    return cache;
  }

  /**
   * Kandidáti seřazení od nejbližšího. Souřadnice se očekávají už zaokrouhlené (volající je
   * prohnal {@link Coordinates#round}) — jsou zároveň cache klíčem, takže se na Overpass
   * posílá přesně to, co se cachuje.
   */
  public OsmStoreCandidateResult nearby(double roundedLat, double roundedLon, double radiusKm) {
    String attribution = messages.get("attribution.osm");
    if (!overpassProperties.isEnabled()) {
      return new OsmStoreCandidateResult(OsmLookupStatus.UNAVAILABLE, List.of(), attribution);
    }

    double radiusM = Math.min(radiusKm, overpassProperties.getMaxRadiusKm()) * 1000;
    String key = String.format(Locale.ROOT, "%.3f|%.3f|%.0f", roundedLat, roundedLon, radiusM);
    List<OsmStoreCandidate> cached = cache().getIfPresent(key);
    if (cached != null) {
      return new OsmStoreCandidateResult(OsmLookupStatus.OK, cached, attribution);
    }

    List<OsmStoreCandidate> fetched = fetch(roundedLat, roundedLon, radiusM);
    if (fetched == null) {
      // Selhání se schválně NEukládá do cache — viz javadoc třídy.
      return new OsmStoreCandidateResult(OsmLookupStatus.UNAVAILABLE, List.of(), attribution);
    }
    cache().put(key, fetched);
    return new OsmStoreCandidateResult(OsmLookupStatus.OK, fetched, attribution);
  }

  /** {@code null} = dotaz selhal (odlišeno od prázdného seznamu, který znamená "v okolí nic"). */
  private List<OsmStoreCandidate> fetch(double lat, double lon, double radiusM) {
    try {
      throttle();
      String query = buildQuery(lat, lon, radiusM);
      OverpassResponse response = restClient().post()
          .uri("")
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body("data=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8))
          .retrieve()
          .body(OverpassResponse.class);
      return toCandidates(response, lat, lon);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (RuntimeException e) {
      // Overpass umí vracet 429 (vyčerpaný slot) i 504 (dotaz se nestihl) — pro appku je to
      // totéž co výpadek: nabídku nemáme, ale nic dalšího se kvůli tomu nesmí zastavit.
      log.warn("Dotaz na Overpass selhal, nabídka obchodů z OSM bude prázdná: {}", e.getMessage());
      return null;
    }
  }

  private String buildQuery(double lat, double lon, double radiusM) {
    long queryTimeoutSec = overpassProperties.getQueryTimeout().toSeconds();
    return String.format(Locale.ROOT, QUERY_TEMPLATE,
        queryTimeoutSec,
        radiusM, lat, lon,
        radiusM, lat, lon,
        overpassProperties.getMaxResults());
  }

  private List<OsmStoreCandidate> toCandidates(OverpassResponse response, double lat, double lon) {
    if (response == null || response.elements() == null) {
      return List.of();
    }
    List<OsmStoreCandidate> candidates = new ArrayList<>();
    for (OverpassElement element : response.elements()) {
      OsmStoreCandidate candidate = toCandidate(element, lat, lon);
      if (candidate != null) {
        candidates.add(candidate);
      }
    }
    candidates.sort(Comparator.comparingDouble(OsmStoreCandidate::distanceMeters));
    return candidates.size() > overpassProperties.getMaxResults()
        ? List.copyOf(candidates.subList(0, overpassProperties.getMaxResults()))
        : List.copyOf(candidates);
  }

  private OsmStoreCandidate toCandidate(OverpassElement element, double lat, double lon) {
    if (element == null || element.tags() == null || element.type() == null || element.id() == null) {
      return null;
    }
    // `node` nese souřadnice přímo, `way`/`relation` je mají v `center` (proto `out center`).
    Double elementLat = element.lat() != null ? element.lat()
        : element.center() != null ? element.center().lat() : null;
    Double elementLon = element.lon() != null ? element.lon()
        : element.center() != null ? element.center().lon() : null;
    if (elementLat == null || elementLon == null) {
      return null;
    }
    Map<String, String> tags = element.tags();
    String name = tags.get("name");
    if (name == null || name.isBlank()) {
      return null;
    }
    String country = tags.get("addr:country");
    return new OsmStoreCandidate(
        name.trim(),
        street(tags),
        firstNonBlank(tags.get("addr:city"), tags.get("addr:town"), tags.get("addr:place"),
            tags.get("addr:village"), tags.get("addr:suburb")),
        tags.get("addr:postcode"),
        country != null ? country.toUpperCase(Locale.ROOT) : null,
        elementLat,
        elementLon,
        element.type() + "/" + element.id(),
        Coordinates.distanceMeters(lat, lon, elementLat, elementLon),
        firstNonBlank(tags.get("brand"), tags.get("operator")));
  }

  /** Ulice a číslo popisné se slepují stejně jako v {@code GeocodingService.toReverseResult}. */
  private String street(Map<String, String> tags) {
    String street = tags.get("addr:street");
    String houseNumber = tags.get("addr:housenumber");
    if (street == null || street.isBlank()) {
      return null;
    }
    return houseNumber == null || houseNumber.isBlank() ? street : street + " " + houseNumber;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  /** Nejvýš 1 dotaz/s (usage policy Overpassu) — sdílené napříč appkou, stejně jako u Nominatimu. */
  private void throttle() throws InterruptedException {
    long minGapMs = 1000;
    long now = System.currentTimeMillis();
    long previous = lastRequestAtMs.getAndUpdate(prev -> Math.max(now, prev + minGapMs));
    long waitMs = previous + minGapMs - now;
    if (waitMs > 0) {
      Thread.sleep(waitMs);
    }
  }

  /** Tvar odpovědi Overpassu — viz https://wiki.openstreetmap.org/wiki/Overpass_API. */
  private record OverpassResponse(@JsonProperty("elements") List<OverpassElement> elements) {
  }

  private record OverpassElement(
      @JsonProperty("type") String type,
      @JsonProperty("id") Long id,
      @JsonProperty("lat") Double lat,
      @JsonProperty("lon") Double lon,
      @JsonProperty("center") OverpassCenter center,
      @JsonProperty("tags") Map<String, String> tags) {
  }

  private record OverpassCenter(
      @JsonProperty("lat") Double lat,
      @JsonProperty("lon") Double lon) {
  }
}
