package cz.kvalitacena.service.fx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import cz.kvalitacena.config.NbsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Národní banka Srbska — jediný zdroj kurzu pro RSD, ČNB ho na lístku nemá (ověřeno živě proti
 * api.cnb.cz při plánování expanze na 13 dalších zemí). Na rozdíl od {@link CnbRateSource}
 * vyžaduje NBS registraci a API klíč ({@link NbsProperties#getApiKey()}); {@link
 * #restClient()} přidává hlavičku {@code x-api-key} jen když je vyplněný, takže appka bez
 * klíče dál běží — RSD kurz chybí, ostatní měny ne (stejná odolnost jako výpadek ČNB).
 *
 * <p><b>Tvar požadavku/odpovědi níže NENÍ ověřený proti reálnému API</b> — NBS na rozdíl od ČNB
 * nemá veřejný sandbox ani dokumentaci dostupnou bez registrace, takže se to (na rozdíl od
 * {@link CnbRateSource}, ověřeného živým `curl` proti api.cnb.cz) nedalo ověřit stejně. Před
 * nasazením do provozu je nutné dopsat/opravit {@link NbsResponse}/{@link NbsRate} podle
 * skutečné odpovědi po získání licence a přepsat testovací fixture na skutečný kontrakt.
 *
 * <p>NBS nekótuje RSD vůči CZK přímo (jako ČNB EUR/PLN/...), ale srbský dinár vůči cizím měnám
 * ze svého číselníku, ve kterém je i CZK (regionální měna, kterou NBS běžně sleduje) —
 * {@link #invert} proto otočí "kolik RSD stojí 1 (nebo {@code numberOfUnits}) CZK" na "kolik
 * CZK stojí 1 RSD", aby výstupní {@link FxRateRow} měl stejný tvar (CZK jako pivot) jako u
 * {@link CnbRateSource} a volající {@link ExchangeRateSyncService} ho nemusel rozlišovat podle zdroje.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NbsRateSource implements ExchangeRateSource {

  private static final String CZK = "CZK";
  private static final String RSD = "RSD";

  private final NbsProperties nbsProperties;

  private volatile RestClient restClient;

  private synchronized RestClient restClient() {
    if (restClient == null) {
      HttpClient httpClient = HttpClient.newBuilder()
          .connectTimeout(nbsProperties.getTimeout())
          .build();
      var requestFactory = new JdkClientHttpRequestFactory(httpClient);
      requestFactory.setReadTimeout(nbsProperties.getTimeout());
      RestClient.Builder builder = RestClient.builder()
          .baseUrl(nbsProperties.getBaseUrl())
          .requestFactory(requestFactory);
      if (nbsProperties.getApiKey() != null && !nbsProperties.getApiKey().isBlank()) {
        builder.defaultHeader("x-api-key", nbsProperties.getApiKey());
      }
      restClient = builder.build();
    }
    return restClient;
  }

  @Override
  public String name() {
    return "NBS";
  }

  @Override
  public List<FxRateRow> fetchDay(LocalDate date) {
    if (nbsProperties.getApiKey() == null || nbsProperties.getApiKey().isBlank()) {
      return List.of(); // Bez registrace appka RSD kurz prostě nemá — viz třídní komentář.
    }
    try {
      NbsResponse response = restClient().get()
          .uri("/exchangeRateList?date={date}", date)
          .header(HttpHeaders.ACCEPT, "application/json")
          .retrieve()
          .body(NbsResponse.class);
      return toRow(response, date).map(List::of).orElseGet(List::of);
    } catch (RestClientException e) {
      log.warn("Kurzovní lístek NBS pro den {} se nepodařilo stáhnout, RSD kurz na ten den chybí: {}",
          date, e.getMessage());
      return List.of();
    }
  }

  @Override
  public List<FxRateRow> fetchYear(int year) {
    // NBS API v tomhle scaffoldu roční endpoint nemá (na rozdíl od ČNB) — u RSD tak backfill/
    // velká mezera prostě jedou po dnech přes fetchDay, žádné volání sem nesměřuje jinak.
    return List.of();
  }

  private Optional<FxRateRow> toRow(NbsResponse response, LocalDate date) {
    if (response == null || response.rates() == null) return Optional.empty();
    return response.rates().stream()
        .filter(r -> CZK.equals(r.currencyCode()))
        .findFirst()
        .map(czk -> new FxRateRow(RSD, 1, date, invert(czk)));
  }

  /** "X RSD za `numberOfUnits` CZK" → "kolik CZK stojí 1 RSD" (viz třídní komentář). */
  private BigDecimal invert(NbsRate czkRate) {
    return BigDecimal.valueOf(czkRate.numberOfUnits())
        .divide(czkRate.exchangeMiddle(), 10, RoundingMode.HALF_UP);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record NbsResponse(@JsonProperty("currencies") List<NbsRate> rates) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record NbsRate(
      @JsonProperty("currencyCode") String currencyCode,
      @JsonProperty("numberOfUnits") int numberOfUnits,
      @JsonProperty("exchangeMiddle") BigDecimal exchangeMiddle) {
  }
}
