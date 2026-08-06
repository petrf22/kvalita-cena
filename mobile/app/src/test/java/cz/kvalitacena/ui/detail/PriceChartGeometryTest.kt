package cz.kvalitacena.ui.detail

import cz.kvalitacena.network.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čistá funkce bez Compose/Androidu — jednotkové testy geometrie grafu (viz plán, "geometrie
 * grafu na obou platformách"). Webový protějšek: frontend price-chart-geometry.spec.ts.
 */
class PriceChartGeometryTest {

  private fun point(day: String, unitPrice: Double) = PricePoint(day, unitPrice, unitPrice, 1, 1)

  @Test
  fun emptyInputProducesEmptyGeometry() {
    val geometry = computeGeometry(emptyList(), 300f, 200f, 24f)
    assertTrue(geometry.markers.isEmpty())
    assertTrue(geometry.stepPath.isEmpty())
  }

  @Test
  fun singlePointIsCenteredAndRangeIsPaddedToAvoidDivisionByZero() {
    val geometry = computeGeometry(listOf(point("2026-07-01", 42.0)), 300f, 200f, 24f)
    assertEquals(1, geometry.markers.size)
    assertEquals(300f / 2f, geometry.markers[0].x, 0.01f)
    assertTrue(geometry.minPrice < geometry.maxPrice) // rozsah roztažený o ±10 %, ne nulový
  }

  @Test
  fun allEqualValuesDoNotDivideByZero() {
    val points = listOf(point("2026-07-01", 10.0), point("2026-07-05", 10.0), point("2026-07-10", 10.0))
    val geometry = computeGeometry(points, 300f, 200f, 24f)
    assertTrue(geometry.minPrice < geometry.maxPrice)
    // Se stejnou cenou musí být všechny body na stejné Y souřadnici (vodorovná čára).
    val ys = geometry.markers.map { it.y }.distinct()
    assertEquals(1, ys.size)
  }

  @Test
  fun xAxisReflectsRealTimeNotPointIndex() {
    // Mezera 1 den vs. mezera 8 dní — indexová osa by udělala z obojího stejný krok, tohle ne.
    val points = listOf(
      point("2026-07-01", 10.0),
      point("2026-07-02", 12.0),
      point("2026-07-10", 14.0),
    )
    val geometry = computeGeometry(points, 300f, 200f, 0f)
    val gapShort = geometry.markers[1].x - geometry.markers[0].x
    val gapLong = geometry.markers[2].x - geometry.markers[1].x
    assertTrue("dlouhá mezera musí být na ose X výrazně širší", gapLong > gapShort * 4)
  }

  @Test
  fun stepPathIsHorizontalThenVertical() {
    // Schodová (step-after) křivka: mezikrok musí mít X druhého bodu a Y prvního bodu.
    val points = listOf(point("2026-07-01", 10.0), point("2026-07-05", 20.0))
    val geometry = computeGeometry(points, 300f, 200f, 0f)
    assertEquals(3, geometry.stepPath.size) // bod0, mezikrok, bod1
    val step = geometry.stepPath[1]
    assertEquals(geometry.markers[1].x, step.x, 0.01f)
    assertEquals(geometry.markers[0].y, step.y, 0.01f)
  }
}
