package cz.kvalitacena.ui.common

import cz.kvalitacena.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoDistanceTest {

  @Test
  fun vzdalenostDvouBrnenskychObchoduOdpovidaSkutecnosti() {
    // Albert Brno-Střed a Lidl Brno-Královo Pole z dev/seed.sql — vzdušnou čarou necelé 4 km.
    val meters = distanceMeters(49.19960, 16.60890, 49.22730, 16.57530)

    assertEquals(3930.0, meters, 40.0)
  }

  @Test
  fun vzdalenostKSobeSamemuJeNula() {
    assertEquals(0.0, distanceMeters(49.1996, 16.6089, 49.1996, 16.6089), 0.0001)
  }

  @Test
  fun popisekPodKilometrJeVMetrechZaokrouhlenyNaDesitky() {
    // Zaokrouhluje se schválně hrubě — "137 m" by předstíralo přesnost, kterou mobilní fix nemá.
    assertEquals(UiText.Res(R.string.store_picker_distance_m, listOf(140)), distanceLabel(137.0))
    assertEquals(UiText.Res(R.string.store_picker_distance_m, listOf(0)), distanceLabel(4.0))
  }

  @Test
  fun popisekNadKilometrJeVKilometrechNaJednoDesetinneMisto() {
    assertEquals(UiText.Res(R.string.store_picker_distance_km, listOf(1.4)), distanceLabel(1449.0))
    assertEquals(UiText.Res(R.string.store_picker_distance_km, listOf(3.9)), distanceLabel(3930.0))
  }

  @Test
  fun hraniceMeziMetryAKilometryJeTisic() {
    assertEquals(UiText.Res(R.string.store_picker_distance_m, listOf(1000)), distanceLabel(999.0))
    assertEquals(UiText.Res(R.string.store_picker_distance_km, listOf(1.0)), distanceLabel(1000.0))
  }

  @Test
  fun okruhZahrnujeIObchodPresneNaHranici() {
    assertTrue(isWithin(500.0, 500))
    assertTrue(isWithin(499.9, 500))
    assertFalse(isWithin(501.0, 500))
  }
}
