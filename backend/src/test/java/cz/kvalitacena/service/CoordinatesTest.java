package cz.kvalitacena.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Coordinates#round} — zaokrouhlování polohy před odesláním Nominatimu/do dotazu
 * {@code nearbyStores}, docs/soukromi.md. Testováno přímo bez sítě; síťové ověření, že do
 * Nominatimu jde už zaokrouhlená hodnota, je v {@link GeocodingServiceTest}.
 */
class CoordinatesTest {

  @Test
  void roundsToGivenDecimalPlaces() {
    assertThat(Coordinates.round(50.08012345, 4)).isEqualTo(50.0801);
    assertThat(Coordinates.round(14.43019999, 3)).isEqualTo(14.43);
  }

  @Test
  void roundsHalfUp() {
    assertThat(Coordinates.round(50.00005, 4)).isEqualTo(50.0001);
  }

  @Test
  void handlesNegativeValues() {
    assertThat(Coordinates.round(-14.43019999, 3)).isEqualTo(-14.43);
  }

  @Test
  void twoNearbyCoordinatesRoundToTheSameCacheKey() {
    // Přesně scénář reverseGeocode() — dvě volání "Použít mou polohu" ze stejného místa,
    // GPS fix se liší v šumu na 6. desetinném místě, po zaokrouhlení musí sednout na sebe.
    double a = 50.08012340;
    double b = 50.08012360;

    assertThat(Coordinates.round(a, 4)).isEqualTo(Coordinates.round(b, 4));
  }

  @Test
  void distanceBetweenTwoBrnoStoresMatchesRealDistance() {
    // Albert Brno-Střed a Lidl Brno-Královo Pole ze seedu (dev/seed.sql) — vzdušnou čarou
    // necelé 4 km. Tolerance 1 % pokrývá rozdíl kulové aproximace proti elipsoidu.
    double meters = Coordinates.distanceMeters(49.19960, 16.60890, 49.22730, 16.57530);

    assertThat(meters).isCloseTo(3930, org.assertj.core.data.Offset.offset(40.0));
  }

  @Test
  void distanceToSelfIsZero() {
    assertThat(Coordinates.distanceMeters(49.19960, 16.60890, 49.19960, 16.60890)).isZero();
  }

  @Test
  void roundingToThreeDecimalsCanShiftThePointByTensOfMeters() {
    // Důvod, proč nearbyStores hledá s okruhem zvětšeným o ROUNDING_SLACK_M
    // (StoreGraphQlController): mřížka 0,001° posune střed hledání o desítky metrů, což je
    // u okruhu 200 m znát.
    // Roh buňky mřížky 0,001° — nejhorší možný případ (půlka úhlopříčky buňky).
    double lat = 49.1995;
    double lon = 16.6005;

    double shift = Coordinates.distanceMeters(lat, lon, Coordinates.round(lat, 3), Coordinates.round(lon, 3));

    assertThat(shift).isGreaterThan(60).isLessThan(70);
  }
}
