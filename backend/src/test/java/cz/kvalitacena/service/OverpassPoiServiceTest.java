package cz.kvalitacena.service;

import cz.kvalitacena.config.OverpassProperties;
import cz.kvalitacena.controller.OsmLookupStatus;
import cz.kvalitacena.controller.OsmStoreCandidate;
import cz.kvalitacena.controller.OsmStoreCandidateResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Výpadek Overpassu NESMÍ shodit výběr obchodu (OverpassPoiService javadoc) — a zároveň se
 * nesmí tvářit jako "v okolí nic není", jinak uživatel založí duplicitu obchodu, který v OSM
 * dávno je.
 */
class OverpassPoiServiceTest {

  private OverpassProperties properties(String baseUrl) {
    OverpassProperties properties = new OverpassProperties();
    properties.setBaseUrl(baseUrl);
    properties.setUserAgent("KvalitaACenaTest/0.1 (test@example.com)");
    properties.setTimeout(Duration.ofSeconds(1));
    properties.setQueryTimeout(Duration.ofSeconds(15));
    properties.setCacheTtl(Duration.ofHours(6));
    properties.setMaxRadiusKm(5);
    properties.setMaxResults(40);
    return properties;
  }

  @Test
  void failsSoftAsUnavailableWhenOverpassIsUnreachable() {
    // Nic tu neposlouchá — spojení je odmítnuto okamžitě, žádné čekání na timeout.
    OverpassPoiService service = new OverpassPoiService(properties("http://127.0.0.1:1"), TestMessages.instance());

    OsmStoreCandidateResult result = service.nearby(50.08, 14.42, 0.5);

    assertThat(result.status()).isEqualTo(OsmLookupStatus.UNAVAILABLE);
    assertThat(result.candidates()).isEmpty();
    // Atribuce se ukazuje i u prázdného výsledku — UI ji musí umět zobrazit vždy stejně.
    assertThat(result.attribution()).isEqualTo(TestMessages.instance().get("attribution.osm"));
  }

  @Test
  void doesNotCacheFailures() {
    OverpassPoiService service = new OverpassPoiService(properties("http://127.0.0.1:1"), TestMessages.instance());

    assertThat(service.nearby(50.08, 14.42, 0.5).status()).isEqualTo(OsmLookupStatus.UNAVAILABLE);
    // Druhý dotaz na tentýž bod musí jít znovu ven, ne vrátit zamrzlé prázdno z cache — jinak
    // by hodinový výpadek Overpassu přežil celé TTL (viz javadoc OverpassPoiService).
    assertThat(service.nearby(50.08, 14.42, 0.5).status()).isEqualTo(OsmLookupStatus.UNAVAILABLE);
  }

  @Test
  void killSwitchSkipsTheRequestEntirely() {
    OverpassProperties properties = properties("http://127.0.0.1:1");
    properties.setEnabled(false);
    OverpassPoiService service = new OverpassPoiService(properties, TestMessages.instance());

    OsmStoreCandidateResult result = service.nearby(50.08, 14.42, 0.5);

    assertThat(result.status()).isEqualTo(OsmLookupStatus.UNAVAILABLE);
    assertThat(result.candidates()).isEmpty();
  }

  /**
   * Parsování odpovědi na tvaru, jaký Overpass opravdu vrací (ověřeno proti veřejné instanci):
   * `node` má souřadnice přímo, `way` je má v `center` (proto `out center`), adresa je
   * rozsypaná v `addr:*` tazích a většina z nich může chybět.
   */
  @Test
  void parsesRealOverpassShape() throws Exception {
    String body = """
        {"elements":[
          {"type":"node","id":2172312233,"lat":49.2009142,"lon":16.6078601,
           "tags":{"addr:city":"Brno","addr:housenumber":"10","addr:postcode":"60200",
                   "addr:street":"Lidická","name":"Ochutnávková pivnice","shop":"beverages"}},
          {"type":"way","id":123456,"center":{"lat":49.1997,"lon":16.6090},
           "tags":{"name":"Albert","shop":"supermarket","brand":"Albert","addr:country":"cz"}},
          {"type":"node","id":999,"lat":49.5,"lon":16.9,"tags":{"shop":"kiosk"}}
        ]}
        """;
    try (StubOverpass stub = new StubOverpass(200, body)) {
      OverpassPoiService service = new OverpassPoiService(properties(stub.url()), TestMessages.instance());

      OsmStoreCandidateResult result = service.nearby(49.1996, 16.6089, 0.5);

      assertThat(result.status()).isEqualTo(OsmLookupStatus.OK);
      // Bezejmenný POI vypadne — bez názvu je pro core.store nepoužitelný.
      assertThat(result.candidates()).hasSize(2);

      OsmStoreCandidate closest = result.candidates().get(0);
      assertThat(closest.name()).isEqualTo("Albert");
      assertThat(closest.lat()).isEqualTo(49.1997);
      assertThat(closest.osmRef()).isEqualTo("way/123456");
      assertThat(closest.country()).isEqualTo("CZ");
      assertThat(closest.brand()).isEqualTo("Albert");

      OsmStoreCandidate farther = result.candidates().get(1);
      assertThat(farther.name()).isEqualTo("Ochutnávková pivnice");
      // Ulice a číslo popisné se slepují stejně jako v GeocodingService.
      assertThat(farther.street()).isEqualTo("Lidická 10");
      assertThat(farther.city()).isEqualTo("Brno");
      assertThat(farther.postalCode()).isEqualTo("60200");
      // Seřazeno od nejbližšího — vzdálenost počítá server z předaného (zaokrouhleného) bodu.
      assertThat(farther.distanceMeters()).isGreaterThan(closest.distanceMeters());
    }
  }

  @Test
  void serverErrorIsUnavailable() throws Exception {
    // 429 (vyčerpaný slot) a 504 (dotaz se nestihl) jsou u Overpassu běžné — pro appku je to
    // totéž co výpadek, nikdy vyhozená výjimka.
    try (StubOverpass stub = new StubOverpass(429, "rate limited")) {
      OverpassPoiService service = new OverpassPoiService(properties(stub.url()), TestMessages.instance());

      assertThat(service.nearby(49.1996, 16.6089, 0.5).status()).isEqualTo(OsmLookupStatus.UNAVAILABLE);
    }
  }

  @Test
  void secondQueryForTheSamePointIsServedFromCache() throws Exception {
    try (StubOverpass stub = new StubOverpass(200, "{\"elements\":[]}")) {
      OverpassPoiService service = new OverpassPoiService(properties(stub.url()), TestMessages.instance());

      service.nearby(49.1996, 16.6089, 0.5);
      service.nearby(49.1996, 16.6089, 0.5);

      // Kvóta Overpassu je cizí — opakovaný dotaz na tentýž bod se ven posílat nesmí.
      assertThat(stub.requestCount()).isEqualTo(1);
    }
  }

  /** Minimální HTTP server místo mocku — ověří i to, že se dotaz opravdu odešle jako POST. */
  private static final class StubOverpass implements AutoCloseable {
    private final com.sun.net.httpserver.HttpServer server;
    private final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();

    StubOverpass(int status, String body) throws java.io.IOException {
      server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
      server.createContext("/api/interpreter", exchange -> {
        requests.incrementAndGet();
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
          out.write(bytes);
        }
      });
      server.start();
    }

    String url() {
      return "http://localhost:" + server.getAddress().getPort() + "/api/interpreter";
    }

    int requestCount() {
      return requests.get();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
