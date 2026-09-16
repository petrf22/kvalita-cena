package cz.kvalitacena.service;

import com.sun.net.httpserver.HttpServer;
import cz.kvalitacena.config.NominatimProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OsmStoreSearchTest {
  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @Test
  void mapsAddressFiltersNonShopsAndCachesRepeatedSearch() throws Exception {
    var calls = new AtomicInteger();
    var request = new AtomicReference<String>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/search", exchange -> {
      calls.incrementAndGet();
      request.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
      byte[] body = """
          [{"lat":"49.18","lon":"16.60","name":"Lidl","category":"shop",
            "display_name":"Lidl, Vídeňská, Brno","osm_type":"way","osm_id":42,
            "address":{"road":"Vídeňská","house_number":"100","city":"Brno",
            "postcode":"61900","country_code":"cz"}},
           {"lat":"49.2","lon":"16.6","name":"Brno","category":"boundary"},
           {"lat":"NaN","lon":"16.6","name":"Invalid","category":"shop",
            "osm_type":"node","osm_id":43,"address":{}}]
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    var service = service("http://127.0.0.1:" + server.getAddress().getPort());
    var result = service.searchStores("Lidl, Brno & okolí", "CZ");
    assertThat(result.available()).isTrue();
    assertThat(result.candidates()).hasSize(1);
    var candidate = result.candidates().getFirst();
    assertThat(candidate.street()).isEqualTo("Vídeňská 100");
    assertThat(candidate.city()).isEqualTo("Brno");
    assertThat(candidate.country()).isEqualTo("CZ");
    assertThat(candidate.osmRef()).isEqualTo("way/42");
    assertThat(candidate.lat()).isEqualTo(49.18);
    assertThat(result.attribution()).isNotBlank();
    assertThat(request.get()).contains("countrycodes=cz", "addressdetails=1", "q=Lidl, Brno & okolí");
    assertThat(service.searchStores("Lidl, Brno & okolí", "CZ").candidates()).isEqualTo(result.candidates());
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void outageIsDifferentFromAnEmptySearch() {
    var service = service("http://127.0.0.1:1");
    assertThat(service.searchStores("Lidl Brno", "CZ").available()).isFalse();
    var empty = service.searchStores(" ", "CZ");
    assertThat(empty.available()).isTrue();
    assertThat(empty.candidates()).isEmpty();
  }

  private GeocodingService service(String url) {
    var properties = new NominatimProperties();
    properties.setBaseUrl(url);
    properties.setUserAgent("KvalitaACenaTest/0.1 (test@example.com)");
    properties.setTimeout(Duration.ofSeconds(1));
    properties.setCacheTtl(Duration.ofDays(1));
    return new GeocodingService(properties, TestMessages.instance());
  }
}
