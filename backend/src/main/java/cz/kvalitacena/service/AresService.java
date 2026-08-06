package cz.kvalitacena.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import cz.kvalitacena.config.AresProperties;
import cz.kvalitacena.controller.CompanyInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;

/**
 * ARES (veřejný rejstřík ekonomických subjektů ČR, https://ares.gov.cz) — předvyplnění
 * formuláře obchodu podle IČO. Na rozdíl od OSM/OFF to NENÍ ODbL data (je to veřejný rejstřík
 * bez share-alike podmínky), takže se nemusí držet mimo core.* — ukládá se ale jen to, co
 * uživatel skutečně potvrdí uložením obchodu, ne celá odpověď ARES.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AresService {

  private final AresProperties aresProperties;

  // Líné vytvoření až při prvním použití — viz GeocodingService, stejný důvod (pořadí
  // @ConfigurationProperties binding vs. @PostConstruct na @Component beanu není zaručené).
  private volatile RestClient restClient;

  private synchronized RestClient restClient() {
    if (restClient == null) {
      HttpClient httpClient = HttpClient.newBuilder()
          .connectTimeout(aresProperties.getTimeout())
          .build();
      var requestFactory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
      requestFactory.setReadTimeout(aresProperties.getTimeout());
      restClient = RestClient.builder()
          .baseUrl(aresProperties.getBaseUrl())
          .requestFactory(requestFactory)
          .build();
    }
    return restClient;
  }

  /** @return údaje o firmě, nebo {@code null} — IČO neexistuje, nebo je ARES nedostupný. */
  public CompanyInfo lookup(String ico) {
    try {
      AresResponse response = restClient().get().uri("/{ico}", ico).retrieve().body(AresResponse.class);
      if (response == null || response.obchodniJmeno() == null) return null;
      Sidlo sidlo = response.sidlo();
      return new CompanyInfo(ico, response.obchodniJmeno(), streetOf(sidlo),
          sidlo == null ? null : sidlo.nazevObce(), postalCodeOf(sidlo));
    } catch (HttpClientErrorException.NotFound e) {
      return null; // IČO v rejstříku neexistuje — ne chyba, jen "nenašlo se nic"
    } catch (RestClientException e) {
      log.warn("Dotaz do ARES pro IČO {} selhal, formulář se vyplní bez předvyplnění: {}", ico, e.getMessage());
      return null;
    }
  }

  private String streetOf(Sidlo sidlo) {
    if (sidlo == null || sidlo.nazevUlice() == null) return null;
    return sidlo.cisloDomovni() == null
        ? sidlo.nazevUlice()
        : sidlo.nazevUlice() + " " + sidlo.cisloDomovni();
  }

  private String postalCodeOf(Sidlo sidlo) {
    return sidlo == null || sidlo.psc() == null ? null : String.format("%05d", sidlo.psc());
  }

  /** Tvar odpovědi ARES /ekonomicke-subjekty/{ico} — jen pole, která appka skutečně používá. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record AresResponse(
      @JsonProperty("ico") String ico,
      @JsonProperty("obchodniJmeno") String obchodniJmeno,
      @JsonProperty("sidlo") Sidlo sidlo) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Sidlo(
      @JsonProperty("nazevUlice") String nazevUlice,
      @JsonProperty("cisloDomovni") Integer cisloDomovni,
      @JsonProperty("nazevObce") String nazevObce,
      @JsonProperty("psc") Integer psc) {
  }
}
