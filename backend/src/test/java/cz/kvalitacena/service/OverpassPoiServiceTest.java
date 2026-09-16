package cz.kvalitacena.service;

import cz.kvalitacena.config.OverpassProperties;
import cz.kvalitacena.controller.OsmLookupStatus;
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
}
