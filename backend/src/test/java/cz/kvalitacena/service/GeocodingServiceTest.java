package cz.kvalitacena.service;

import cz.kvalitacena.config.NominatimProperties;
import cz.kvalitacena.controller.OsmLookupStatus;
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

    // Výpadek se NESMÍ tvářit jako "tady žádná adresa není" — klient by jinak klepnutím do
    // mapy smazal vyplněnou adresu prázdnem.
    assertThat(result.status()).isEqualTo(OsmLookupStatus.UNAVAILABLE);
    assertThat(result.street()).isNull();
    assertThat(result.city()).isNull();
    assertThat(result.postalCode()).isNull();
    assertThat(result.osmRef()).isNull();
    // Atribuce se ukazuje i u prázdného výsledku — UI ji musí umět zobrazit vždy stejně
    // (docs/lokalizace.md — text jde přes Messages/messages/attribution*.properties).
    assertThat(result.attribution()).isEqualTo(TestMessages.instance().get("attribution.osm"));
  }

  @Test
  void addressLookupTellsOutageFromEmptyResult() {
    NominatimProperties properties = new NominatimProperties();
    properties.setBaseUrl("http://127.0.0.1:1");
    properties.setUserAgent("KvalitaACenaTest/0.1 (test@example.com)");
    properties.setTimeout(Duration.ofSeconds(1));
    properties.setCacheTtl(Duration.ofDays(1));
    GeocodingService service = new GeocodingService(properties, TestMessages.instance());

    // Výpadek je prázdný Optional (UNAVAILABLE pro osmStoresAtAddress), ne „adresa neexistuje",
    // a necachuje se — druhý dotaz to zkusí znovu a dopadne stejně, ne z cache prázdným seznamem.
    assertThat(service.lookup("Hlavní 1", "Praha", null, "CZ")).isEmpty();
    assertThat(service.lookup("Hlavní 1", "Praha", null, "CZ")).isEmpty();
    // geocodeAddress se chová jako dřív: výpadek je prázdný seznam, nikdy výjimka.
    assertThat(service.geocode("Hlavní 1", "Praha", null, "CZ").candidates()).isEmpty();
  }
}
