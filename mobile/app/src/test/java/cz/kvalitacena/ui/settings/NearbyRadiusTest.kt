package cz.kvalitacena.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearbyRadiusTest {
  @Test
  fun radiusRespectsApiBoundsAndConvertsMetersToKilometers() {
    assertEquals(100, parseNearbyRadius("100"))
    assertEquals(25000, parseNearbyRadius("25000"))
    assertEquals(0.5, parseNearbyRadius(" 500 ")!! / 1000.0, 0.0)
  }

  @Test
  fun invalidInputCannotReplaceSavedRadius() {
    listOf("", "0", "99", "25001", "-500", "500.5", "NaN", "999999999999").forEach {
      assertNull(parseNearbyRadius(it))
    }
  }
}
