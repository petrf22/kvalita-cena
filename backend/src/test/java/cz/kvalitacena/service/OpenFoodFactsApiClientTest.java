package cz.kvalitacena.service;

import com.sun.net.httpserver.HttpServer;
import cz.kvalitacena.config.OpenFoodFactsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenFoodFactsApiClientTest {

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void fetchUsesV3FieldsUserAgentAndSanitizesResponse() throws Exception {
    AtomicReference<String> userAgent = new AtomicReference<>();
    AtomicReference<String> query = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v3/product/8594001234578", exchange -> {
      userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
      query.set(exchange.getRequestURI().getRawQuery());
      byte[] body = ("""
          {"product":{"product_name":" Máslo ","brands":"První, Druhá",\
          "product_quantity":"250","product_quantity_unit":"g",\
          "categories_tags":["en:dairies","en:butters","neplatný tag"],\
          "image_front_url":"https://images.openfoodfacts.org/front.jpg",\
          "image_front_small_url":"http://example.org/wrong.jpg",\
          "additives_tags":["en:e330","en:e150c","neplatný tag"],\
          "rev":42,"last_modified_t":1700000000}}
          """).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();

    OpenFoodFactsProperties properties = new OpenFoodFactsProperties();
    properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    properties.setUserAgent("KvalitaACenaTest/1.0 (test@example.com)");
    properties.setTimeout(Duration.ofSeconds(2));

    OffRemoteProduct product = new OpenFoodFactsApiClient(properties).fetch("8594001234578").orElseThrow();

    assertThat(userAgent.get()).isEqualTo(properties.getUserAgent());
    assertThat(query.get()).contains("fields=");
    assertThat(product.productName()).isEqualTo("Máslo");
    assertThat(product.brandName()).isEqualTo("První");
    assertThat(product.productQuantityUnit()).isEqualTo("G");
    assertThat(product.categoryTags()).containsExactly("en:dairies", "en:butters");
    assertThat(product.imageFrontUrl()).startsWith("https://images.openfoodfacts.org/");
    assertThat(product.imageFrontSmallUrl()).isNull();
    assertThat(product.additivesTags()).containsExactly("en:e330", "en:e150c");
  }
}
