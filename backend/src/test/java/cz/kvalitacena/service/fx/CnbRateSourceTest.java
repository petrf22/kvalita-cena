package cz.kvalitacena.service.fx;

import com.sun.net.httpserver.HttpServer;
import cz.kvalitacena.config.FxProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ČNB nemá sandbox/mock API, takže se tu vůči skutečnému tvaru odpovědi ověřuje jen parsování —
 * live endpoint je ověřený ručně (curl), viz plán. Fixture obsahuje EMU/EUR (amount=1) i
 * Hungary/HUF (amount=100), aby test pokryl i měnu, kterou appka nesleduje (HUF se zahazuje až
 * v ExchangeRateSyncService, ne tady — CnbRateSource je čistě transportní vrstva) a pole
 * navíc (country/currency/order), která {@code @JsonIgnoreProperties} musí přejít beze zdi.
 */
class CnbRateSourceTest {

  private static final String FIXTURE = """
      {"rates":[
        {"country":"EMU","currency":"euro","amount":1,"currencyCode":"EUR","validFor":"2026-08-10","rate":24.255,"order":152},
        {"country":"Hungary","currency":"forint","amount":100,"currencyCode":"HUF","validFor":"2026-08-10","rate":6.668,"order":152}
      ]}""";

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void fetchDayParsesRatesIncludingUntrackedCurrencyAndIgnoresExtraFields() throws IOException {
    AtomicReference<String> requestedPath = new AtomicReference<>();
    server = startServer((exchange, out) -> {
      requestedPath.set(exchange.getRequestURI().toString());
      out.write(FIXTURE.getBytes(StandardCharsets.UTF_8));
    });

    CnbRateSource source = new CnbRateSource(propertiesFor(server));
    List<ExchangeRateSource.FxRateRow> rows = source.fetchDay(LocalDate.of(2026, 8, 10));

    assertThat(requestedPath.get()).contains("/exrates/daily").contains("date=2026-08-10").contains("lang=EN");
    assertThat(rows).hasSize(2);
    ExchangeRateSource.FxRateRow eur = rows.stream().filter(r -> r.currencyCode().equals("EUR")).findFirst().orElseThrow();
    assertThat(eur.amount()).isEqualTo(1);
    assertThat(eur.rate()).isEqualByComparingTo(new BigDecimal("24.255"));
    assertThat(eur.validFor()).isEqualTo(LocalDate.of(2026, 8, 10));
    // HUF projde parsováním beze změny — filtrování na sledované měny dělá až volající.
    ExchangeRateSource.FxRateRow huf = rows.stream().filter(r -> r.currencyCode().equals("HUF")).findFirst().orElseThrow();
    assertThat(huf.amount()).isEqualTo(100);
    assertThat(huf.rate()).isEqualByComparingTo(new BigDecimal("6.668"));
  }

  @Test
  void fetchYearUsesYearEndpoint() throws IOException {
    AtomicReference<String> requestedPath = new AtomicReference<>();
    server = startServer((exchange, out) -> {
      requestedPath.set(exchange.getRequestURI().toString());
      out.write(FIXTURE.getBytes(StandardCharsets.UTF_8));
    });

    CnbRateSource source = new CnbRateSource(propertiesFor(server));
    List<ExchangeRateSource.FxRateRow> rows = source.fetchYear(2026);

    assertThat(requestedPath.get()).contains("/exrates/daily-year").contains("year=2026");
    assertThat(rows).hasSize(2);
  }

  @Test
  void fetchDayFailsSoftWhenCnbIsUnreachable() {
    FxProperties properties = new FxProperties();
    // Nic tu neposlouchá — spojení je odmítnuto okamžitě, žádné čekání na timeout.
    properties.setBaseUrl("http://127.0.0.1:1");
    properties.setTimeout(Duration.ofSeconds(1));

    CnbRateSource source = new CnbRateSource(properties);

    assertThat(source.fetchDay(LocalDate.of(2026, 8, 10))).isEmpty();
    assertThat(source.fetchYear(2026)).isEmpty();
  }

  private FxProperties propertiesFor(HttpServer server) {
    FxProperties properties = new FxProperties();
    properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    properties.setTimeout(Duration.ofSeconds(5));
    return properties;
  }

  private interface Handler {
    void handle(com.sun.net.httpserver.HttpExchange exchange, OutputStream out) throws IOException;
  }

  private HttpServer startServer(Handler handler) throws IOException {
    HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    httpServer.createContext("/", exchange -> {
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream out = exchange.getResponseBody()) {
        handler.handle(exchange, out);
      }
    });
    httpServer.start();
    return httpServer;
  }
}
