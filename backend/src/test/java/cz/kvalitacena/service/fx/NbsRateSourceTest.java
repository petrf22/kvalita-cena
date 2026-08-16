package cz.kvalitacena.service.fx;

import com.sun.net.httpserver.HttpServer;
import cz.kvalitacena.config.NbsProperties;
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
 * Na rozdíl od {@link CnbRateSourceTest} tenhle test NEOVĚŘUJE parsování proti skutečnému NBS —
 * NBS vyžaduje registraci a API klíč, který appka nemá (viz {@link NbsRateSource} třídní
 * komentář). Fixture je tak jen vlastní vymyšlený, zdokumentovaný kontrakt — ověřuje se logika
 * inverze "RSD za CZK" → "CZK za 1 RSD" a odolnost proti chybějícímu klíči/výpadku, ne
 * shoda s reálnou odpovědí. Před nasazením do provozu je nutné přepsat na skutečný tvar.
 */
class NbsRateSourceTest {

  private static final String FIXTURE = """
      {"currencies":[
        {"currencyCode":"EUR","numberOfUnits":1,"exchangeMiddle":117.17},
        {"currencyCode":"CZK","numberOfUnits":1,"exchangeMiddle":4.532}
      ]}""";

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void fetchDayInvertsCzkRateIntoCzkPerRsd() throws IOException {
    AtomicReference<String> requestedPath = new AtomicReference<>();
    server = startServer((exchange, out) -> {
      requestedPath.set(exchange.getRequestURI().toString());
      out.write(FIXTURE.getBytes(StandardCharsets.UTF_8));
    });

    NbsRateSource source = new NbsRateSource(propertiesFor(server, "test-key"));
    List<ExchangeRateSource.FxRateRow> rows = source.fetchDay(LocalDate.of(2026, 8, 14));

    assertThat(requestedPath.get()).contains("date=2026-08-14");
    assertThat(rows).hasSize(1);
    ExchangeRateSource.FxRateRow rsd = rows.getFirst();
    assertThat(rsd.currencyCode()).isEqualTo("RSD");
    assertThat(rsd.amount()).isEqualTo(1);
    // 1 CZK = 4.532 RSD → 1 RSD = 1/4.532 CZK.
    assertThat(rsd.rate()).isEqualByComparingTo(BigDecimal.ONE.divide(new BigDecimal("4.532"), 10, java.math.RoundingMode.HALF_UP));
  }

  @Test
  void nameIsNbs() {
    assertThat(new NbsRateSource(propertiesFor(null, "test-key")).name()).isEqualTo("NBS");
  }

  /** Bez API klíče appka NBS vůbec nevolá — RSD kurz na ten den chybí, appka nespadne. */
  @Test
  void fetchDayWithoutApiKeyReturnsEmptyWithoutCallingNbs() {
    NbsRateSource source = new NbsRateSource(propertiesFor(null, null));

    assertThat(source.fetchDay(LocalDate.of(2026, 8, 14))).isEmpty();
    assertThat(source.fetchYear(2026)).isEmpty();
  }

  @Test
  void fetchDayFailsSoftWhenNbsIsUnreachable() {
    NbsProperties properties = new NbsProperties();
    properties.setBaseUrl("http://127.0.0.1:1");
    properties.setApiKey("test-key");
    properties.setTimeout(Duration.ofSeconds(1));

    NbsRateSource source = new NbsRateSource(properties);

    assertThat(source.fetchDay(LocalDate.of(2026, 8, 14))).isEmpty();
  }

  @Test
  void fetchDayWithoutCzkInResponseReturnsEmpty() throws IOException {
    server = startServer((exchange, out) -> out.write(
        """
        {"currencies":[{"currencyCode":"EUR","numberOfUnits":1,"exchangeMiddle":117.17}]}
        """.getBytes(StandardCharsets.UTF_8)));

    NbsRateSource source = new NbsRateSource(propertiesFor(server, "test-key"));

    assertThat(source.fetchDay(LocalDate.of(2026, 8, 14))).isEmpty();
  }

  private NbsProperties propertiesFor(HttpServer server, String apiKey) {
    NbsProperties properties = new NbsProperties();
    properties.setBaseUrl(server == null ? "http://127.0.0.1:1" : "http://127.0.0.1:" + server.getAddress().getPort());
    properties.setApiKey(apiKey);
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
