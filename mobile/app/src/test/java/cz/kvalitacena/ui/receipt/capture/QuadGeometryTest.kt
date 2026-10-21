package cz.kvalitacena.ui.receipt.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadGeometryTest {

  private val tilted = Quad(Pt(110.0, 40.0), Pt(360.0, 60.0), Pt(340.0, 600.0), Pt(90.0, 580.0))

  @Test
  fun seradiRohyBezOhleduNaVstupniPoradi() {
    val shuffled = listOf(tilted.br, tilted.tl, tilted.bl, tilted.tr)
    assertEquals(tilted, orderCorners(shuffled))
  }

  @Test
  fun plochaObdelniku() {
    val rect = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 5.0), Pt(0.0, 5.0))
    assertEquals(50.0, polygonArea(rect), 1e-9)
  }

  @Test
  fun konvexnostRozeznaMotylka() {
    assertTrue(isConvex(tilted))
    val bowtie = Quad(Pt(0.0, 0.0), Pt(10.0, 10.0), Pt(10.0, 0.0), Pt(0.0, 10.0))
    assertFalse(isConvex(bowtie))
  }

  @Test
  fun maleNeboDegenerovaneCtyruhelnikyNejsouUctenka() {
    assertTrue(isPlausibleReceiptQuad(tilted, 480, 640))
    val tiny = Quad(Pt(10.0, 10.0), Pt(40.0, 10.0), Pt(40.0, 40.0), Pt(10.0, 40.0))
    assertFalse(isPlausibleReceiptQuad(tiny, 480, 640))
    val sliver = Quad(Pt(0.0, 0.0), Pt(480.0, 0.0), Pt(480.0, 3.0), Pt(0.0, 3.0))
    assertFalse(isPlausibleReceiptQuad(sliver, 480, 640))
  }

  @Test
  fun narovnanyRozmerBereDelsiProtejsiStrany() {
    val (width, height) = straightenedSize(Quad(Pt(0.0, 0.0), Pt(300.0, 0.0), Pt(320.0, 800.0), Pt(10.0, 790.0)))
    assertEquals(310, width)
    assertEquals(800, height)
  }

  @Test
  fun sirkaSeOmeziJenShora() {
    assertEquals(1600 to 4000, limitWidth(2400, 6000))
    assertEquals(900 to 3000, limitWidth(900, 3000))
  }

  @Test
  fun svetlyPruhPapiruNaTmavemStole() {
    val profile = DoubleArray(100) { x -> if (x in 30..75) 220.0 else 60.0 }
    assertEquals(30..75, findBrightBand(profile))
  }

  @Test
  fun pruhBezKontrastuNeboUzkyNeplati() {
    val lowContrast = DoubleArray(100) { x -> if (x in 30..75) 130.0 else 120.0 }
    assertNull(findBrightBand(lowContrast))
    val narrow = DoubleArray(100) { x -> if (x in 45..52) 220.0 else 60.0 }
    assertNull(findBrightBand(narrow))
    val wholeFrame = DoubleArray(100) { 220.0 }
    assertNull(findBrightBand(wholeFrame))
  }

  @Test
  fun papirAzKOkrajiZaberu() {
    val profile = DoubleArray(100) { x -> if (x <= 70) 220.0 else 60.0 }
    val band = findBrightBand(profile)
    assertNotNull(band)
    assertEquals(0, band!!.first)
    assertEquals(70, band.last)
  }

  @Test
  fun bocniHranySeProtahnouNaCelouVysku() {
    val quad = quadFromSideEdges(100..300, 120..320, topY = 100.0, bottomY = 500.0, frameHeight = 600)
    assertEquals(Pt(95.0, 0.0), quad.tl)
    assertEquals(Pt(295.0, 0.0), quad.tr)
    assertEquals(Pt(325.0, 600.0), quad.br)
    assertEquals(Pt(125.0, 600.0), quad.bl)
  }

  @Test
  fun otoceniZpetDoSouradnicSenzoru() {
    // Senzor 640 × 480 na šířku, telefon na výšku → otočeno o 90°, snímek na výšku 480 × 640.
    assertEquals(480 to 640, uprightSize(640, 480, 90))
    // Levý horní roh snímku na výšku je levý dolní roh senzoru.
    assertEquals(Pt(0.0, 480.0), uprightToSensor(Pt(0.0, 0.0), 90, 640, 480))
    // Pravý horní roh na výšku (480, 0) je levý horní roh senzoru.
    assertEquals(Pt(0.0, 0.0), uprightToSensor(Pt(480.0, 0.0), 90, 640, 480))
    assertEquals(Pt(640.0, 0.0), uprightToSensor(Pt(0.0, 0.0), 270, 640, 480))
    assertEquals(Pt(630.0, 470.0), uprightToSensor(Pt(10.0, 10.0), 180, 640, 480))
    assertEquals(Pt(5.0, 7.0), uprightToSensor(Pt(5.0, 7.0), 0, 640, 480))
  }

  @Test
  fun vyplneniNahleduOrizneNaStred() {
    // Snímek 480 × 640 do náhledu 1080 × 1920: měřítko 3 (výška), vodorovně přečnívá o 180 px.
    val p = imageToFillCenterView(Pt(0.0, 0.0), 480, 640, 1080.0, 1920.0)
    assertEquals(-180.0, p.x, 1e-9)
    assertEquals(0.0, p.y, 1e-9)
    val center = imageToFillCenterView(Pt(240.0, 320.0), 480, 640, 1080.0, 1920.0)
    assertEquals(540.0, center.x, 1e-9)
    assertEquals(960.0, center.y, 1e-9)
  }

  @Test
  fun rozrezaniDlouheUctenkyJakoVDokumentaci() {
    // docs/ai.md: Billa 940 × 3430 px → 5 dílů s ~40% překryvem (díl ~1009 px).
    val ranges = tileRanges(height = 3430, tileHeight = 1009)
    assertEquals(5, ranges.size)
    assertEquals(0, ranges.first().first)
    assertEquals(3429, ranges.last().last)
    ranges.forEach { assertEquals(1009, it.last - it.first + 1) }
    ranges.zipWithNext().forEach { (a, b) ->
      val overlap = a.last - b.first + 1
      assertTrue("překryv $overlap px je aspoň 40 % dílu", overlap >= 0.4 * 1009 - 1)
    }
  }

  @Test
  fun ctvercoveDilyMajiVzdyDostPrekryvu() {
    val ranges = tileRanges(height = 3430, tileHeight = 940)
    assertEquals(6, ranges.size)
    assertEquals(3429, ranges.last().last)
    ranges.zipWithNext().forEach { (a, b) -> assertTrue(a.last - b.first + 1 >= 0.4 * 940 - 1) }
  }

  @Test
  fun kratkyObrazekZustaneVcelku() {
    assertEquals(listOf(0 until 700), tileRanges(height = 700, tileHeight = 940))
  }

  /** Řádky textu po 30 px s mezerou 10 px: inkoust v řádcích 0–29, 40–69, … */
  private fun textRows(height: Int) = IntArray(height) { row -> if (row % 40 < 30) 200 else 0 }

  @Test
  fun rezSePosuneDoMezeryMeziRadky() {
    val ink = textRows(400)
    // Řádek 115 je uprostřed textu (80–109 text, 110–119 mezera… 115 je v mezeře).
    assertEquals(115, snapToGap(ink, 115, 10))
    // Řádek 95 je uprostřed textu (80–109) → nejbližší mezera v okně ±20 je 110 (79 je dál).
    assertEquals(110, snapToGap(ink, 95, 20))
  }

  @Test
  fun dilyZGalerieRezouMezeramiAPrekryvZustava() {
    val ink = textRows(3430)
    val ranges = gapAlignedTileRanges(ink, tileHeight = 1009)
    assertEquals(5, ranges.size)
    assertEquals(0, ranges.first().first)
    assertEquals(3429, ranges.last().last)
    ranges.drop(1).forEach { assertEquals("horní řez dílu $it je v mezeře", 0, ink[it.first]) }
    ranges.dropLast(1).forEach { assertEquals("dolní řez dílu $it je v mezeře", 0, ink[it.last]) }
    ranges.zipWithNext().forEach { (a, b) -> assertTrue(a.last - b.first + 1 >= 0.35 * 1009) }
  }

  @Test
  fun dilZKameryOrizneNapulPrerizleRadky() {
    // Záběr začíná uprostřed řádku textu (posun o 15 px) a končí taky uprostřed.
    val ink = IntArray(395) { row -> if ((row + 15) % 40 < 30) 200 else 0 }
    val kept = trimToTextGaps(ink)
    assertEquals(0, ink[kept.first])
    assertEquals(0, ink[kept.last])
    assertTrue(kept.first > 0)
    assertTrue(kept.last < 394)
  }
}
