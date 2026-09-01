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
}
