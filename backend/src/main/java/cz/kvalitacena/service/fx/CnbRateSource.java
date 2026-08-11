package cz.kvalitacena.service.fx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import cz.kvalitacena.config.FxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.List;

/**
 * Kurzovní lístek ČNB (https://api.cnb.cz/cnbapi) — veřejné REST API bez klíče. Denní i roční
 * endpoint vrací stejný obálkový tvar {@code {"rates":[...]}}, jen denní má navíc pole
 * country/currency/order, která appka nepotřebuje ({@code @JsonIgnoreProperties}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CnbRateSource implements ExchangeRateSource {

  private final FxProperties fxProperties;

  // Líné vytvoření až při prvním použití — stejný vzorec a stejný důvod jako AresService/
  // GeocodingService (pořadí @ConfigurationProperties binding vs. @PostConstruct není zaručené).
  private volatile RestClient restClient;

  private synchronized RestClient restClient() {
    if (restClient == null) {
      HttpClient httpClient = HttpClient.newBuilder()
          .connectTimeout(fxProperties.getTimeout())
          .build();
      var requestFactory = new JdkClientHttpRequestFactory(httpClient);
      requestFactory.setReadTimeout(fxProperties.getTimeout());
      restClient = RestClient.builder()
          .baseUrl(fxProperties.getBaseUrl())
          .requestFactory(requestFactory)
          .build();
    }
    return restClient;
  }

  @Override
  public List<FxRateRow> fetchDay(LocalDate date) {
    try {
      CnbResponse response = restClient().get()
          .uri("/exrates/daily?date={date}&lang=EN", date)
          .retrieve()
          .body(CnbResponse.class);
      return toRows(response);
    } catch (RestClientException e) {
      log.warn("Kurzovní lístek ČNB pro den {} se nepodařilo stáhnout, přepočet poběží se starším kurzem: {}",
          date, e.getMessage());
      return List.of();
    }
  }

  @Override
  public List<FxRateRow> fetchYear(int year) {
    try {
      CnbResponse response = restClient().get()
          .uri("/exrates/daily-year?year={year}&lang=EN", year)
          .retrieve()
          .body(CnbResponse.class);
      return toRows(response);
    } catch (RestClientException e) {
      log.warn("Roční kurzovní lístek ČNB {} se nepodařilo stáhnout: {}", year, e.getMessage());
      return List.of();
    }
  }

  private List<FxRateRow> toRows(CnbResponse response) {
    if (response == null || response.rates() == null) return List.of();
    return response.rates().stream()
        .map(r -> new FxRateRow(r.currencyCode(), r.amount(), r.validFor(), r.rate()))
        .toList();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record CnbResponse(@JsonProperty("rates") List<CnbRate> rates) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record CnbRate(
      @JsonProperty("currencyCode") String currencyCode,
      @JsonProperty("amount") int amount,
      @JsonProperty("validFor") LocalDate validFor,
      @JsonProperty("rate") BigDecimal rate) {
  }
}
