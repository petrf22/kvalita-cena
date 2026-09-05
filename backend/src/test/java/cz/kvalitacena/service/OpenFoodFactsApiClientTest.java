package cz.kvalitacena.service;

import com.sun.net.httpserver.HttpServer;
import cz.kvalitacena.config.OpenFoodFactsProperties;
import cz.kvalitacena.db.entity.OffImageKind;
import cz.kvalitacena.db.entity.OffProductImage;
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

    OffRemoteProduct product = new OpenFoodFactsApiClient(properties, TestI18n.properties()).fetch("8594001234578").orElseThrow();

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

  /**
   * Reálný tvar odpovědi pro EAN 8586007690441 (Magnesia, ověřeno živě 2026-09): OFF má
   * lang='en', product_name je počítaný fallback v češtině a německá varianta žije zvlášť
   * v product_name_de. Přesně tenhle produkt se v české appce nabízel německy.
   */
  @Test
  void fetchAsksForEveryAppLanguageAndKeepsNamesAndImagesPerLanguage() throws Exception {
    AtomicReference<String> query = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v3/product/8586007690441", exchange -> {
      query.set(exchange.getRequestURI().getRawQuery());
      byte[] body = ("""
          {"product":{"lang":"en","product_name":"jemně perlivá",\
          "product_name_cs":"jemně perlivá","product_name_de":"Magnesia",\
          "product_name_de_imported":"Nesmysl","product_name_xx":"Nesmysl",\
          "image_front_url":"https://images.openfoodfacts.org/front_cs.4.400.jpg",\
          "selected_images":{\
            "front":{"display":{"cs":"https://images.openfoodfacts.org/front_cs.4.400.jpg",\
                                "de":"https://images.openfoodfacts.org/front_de.3.400.jpg"},\
                     "small":{"cs":"https://images.openfoodfacts.org/front_cs.4.200.jpg"}},\
            "ingredients":{"display":{"en":"https://images.openfoodfacts.org/ingredients_en.12.400.jpg"}},\
            "nutrition":{"display":{"sk":"https://images.openfoodfacts.org/nutrition_sk.9.400.jpg"}}}}}
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

    OffRemoteProduct product =
        new OpenFoodFactsApiClient(properties, TestI18n.properties()).fetch("8586007690441").orElseThrow();

    // fields nezná zástupný znak, jazyky se musí vyjmenovat — a to podle supported-locales.
    assertThat(query.get()).contains("product_name_cs").contains("product_name_de")
        .contains("product_name_sk").contains("product_name_en").contains("product_name_pl")
        .contains("selected_images");
    assertThat(product.lang()).isEqualTo("en");
    assertThat(product.nameLocales()).containsExactly("cs", "de", "en", "pl", "sk");
    // product_name_de_imported není jazyk a "xx" je v OFF značka "jazykově neutrální" —
    // ani jedno do mapy po jazycích nepatří.
    assertThat(product.names()).containsOnlyKeys("cs", "de");
    assertThat(product.names()).containsEntry("de", "Magnesia");

    assertThat(product.images()).hasSize(3);
    OffProductImage frontCs = product.images().stream()
        .filter(image -> image.getKind() == OffImageKind.FRONT && "cs".equals(image.getLang()))
        .findFirst().orElseThrow();
    assertThat(frontCs.getUrl()).endsWith("front_cs.4.400.jpg");
    assertThat(frontCs.getSmallUrl()).endsWith("front_cs.4.200.jpg");
    // Německá varianta small nemá — smí zůstat null, fotka se stejně otevírá v plné velikosti.
    assertThat(product.images().stream()
        .filter(image -> "de".equals(image.getLang())).findFirst().orElseThrow().getSmallUrl()).isNull();
    // nutrition appka nikde nezobrazuje, takže se neukládá.
    assertThat(product.images()).noneMatch(image -> image.getUrl().contains("nutrition"));
  }
}
