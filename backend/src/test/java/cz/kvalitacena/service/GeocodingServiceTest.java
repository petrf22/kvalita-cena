package cz.kvalitacena.service;

import cz.kvalitacena.config.NominatimProperties;
import cz.kvalitacena.controller.ReverseGeocodeResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Výpadek/timeout Nominatimu NESMÍ shodit editaci obchodu (GeocodingService javadoc,
 * docs/soukromi.md) — {@code reverseGeocode} musí i při nedostupném serveru vrátit prázdný
 * výsledek, nikdy vyhodit výjimku.
 */
class GeocodingServiceTest {

  @Test
  void reverseGeocodeFailsSoftWhenNominatimIsUnreachable() {
    NominatimProperties properties = new NominatimProperties();
    // Nic tu neposlouchá — spojení je odmítnuto okamžitě, žádné čekání na timeout.
    properties.setBaseUrl("http://127.0.0.1:1");
    properties.setUserAgent("KvalitaACenaTest/0.1 (test@example.com)");
    properties.setTimeout(Duration.ofSeconds(1));
    properties.setCacheTtl(Duration.ofDays(1));

    GeocodingService service = new GeocodingService(properties, TestMessages.instance());

    ReverseGeocodeResult result = service.reverseGeocode(50.08, 14.42);

    assertThat(result.street()).isNull();
    assertThat(result.city()).isNull();
    assertThat(result.postalCode()).isNull();
    assertThat(result.osmRef()).isNull();
    // Atribuce se ukazuje i u prázdného výsledku — UI ji musí umět zobrazit vždy stejně
    // (docs/lokalizace.md — text jde přes Messages/messages/attribution*.properties).
    assertThat(result.attribution()).isEqualTo(TestMessages.instance().get("attribution.osm"));
  }
}
