package cz.kvalitacena.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearbyRadiusStoreTest {

  @Test
  fun ulozenaPredvolbaSeNacteZpatky() {
    assertEquals(NearbyRadius.M200, nearbyRadiusFromMeters(200))
    assertEquals(NearbyRadius.KM5, nearbyRadiusFromMeters(5000))
  }

  @Test
  fun neznamaHodnotaSpadneNaNejblizsiPredvolbu() {
    // Škála se mezi verzemi může změnit — "zhruba to, co uživatel chtěl" je lepší odpověď
    // než tichý návrat na výchozích 500 m.
    assertEquals(NearbyRadius.M500, nearbyRadiusFromMeters(600))
    assertEquals(NearbyRadius.KM1, nearbyRadiusFromMeters(900))
    assertEquals(NearbyRadius.KM5, nearbyRadiusFromMeters(9000))
  }

  @Test
  fun poskozenaHodnotaDaVychozich500m() {
    assertEquals(DEFAULT_NEARBY_RADIUS, nearbyRadiusFromMeters(0))
    assertEquals(DEFAULT_NEARBY_RADIUS, nearbyRadiusFromMeters(-1))
  }

  @Test
  fun sirsiOkoliJdeOJedenKrokNahoruANaStropuKonci() {
    assertEquals(NearbyRadius.M500, widerNearbyRadius(NearbyRadius.M200))
    assertEquals(NearbyRadius.KM1, widerNearbyRadius(NearbyRadius.M500))
    assertNull(widerNearbyRadius(NearbyRadius.KM5))
  }

  @Test
  fun radiusKmOdpovidaMetrum() {
    assertEquals(0.2, NearbyRadius.M200.radiusKm, 0.0001)
    assertEquals(5.0, NearbyRadius.KM5.radiusKm, 0.0001)
  }
}
