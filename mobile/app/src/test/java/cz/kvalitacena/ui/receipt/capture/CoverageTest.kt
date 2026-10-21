package cz.kvalitacena.ui.receipt.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageTest {

  @Test
  fun prvniDilHnedJakJeUctenkaOstra() {
    val tracker = CoverageTracker()
    assertFalse(tracker.shouldCapture(receiptFound = false, sharp = true))
    assertFalse(tracker.shouldCapture(receiptFound = true, sharp = false))
    assertTrue(tracker.shouldCapture(receiptFound = true, sharp = true))
  }

  @Test
  fun dalsiDilAzPoPosunuOSedesatProcent() {
    val tracker = CoverageTracker()
    tracker.onPartSaved()
    // Telefon jede dolů → obsah ujíždí nahoru → záporné dy; deset snímků po 5 % výšky.
    repeat(10) { tracker.addFrameShift(dyPixels = -32.0, frameHeight = 640, response = 0.5) }
    assertEquals(0.5, tracker.shiftSinceLastPart, 1e-9)
    assertFalse(tracker.shouldCapture(receiptFound = true, sharp = true))
    assertEquals(0.5f / 0.6f, tracker.progressToNextPart, 1e-6f)

    repeat(2) { tracker.addFrameShift(dyPixels = -32.0, frameHeight = 640, response = 0.5) }
    assertTrue(tracker.shouldCapture(receiptFound = true, sharp = true))
    tracker.onPartSaved()
    assertEquals(2, tracker.partCount)
    assertEquals(0f, tracker.progressToNextPart, 0f)
  }

  @Test
  fun rozmazanySnimekPoPosunuSeNeulozi() {
    val tracker = CoverageTracker()
    tracker.onPartSaved()
    tracker.addFrameShift(dyPixels = -400.0, frameHeight = 640, response = 0.5)
    assertFalse(tracker.shouldCapture(receiptFound = true, sharp = false))
    assertEquals(CaptureHint.HOLD_STILL, tracker.hint(receiptFound = true, sharp = false))
  }

  @Test
  fun posunPresCelyZaberChceVratitTelefonVys() {
    val tracker = CoverageTracker()
    tracker.onPartSaved()
    tracker.addFrameShift(dyPixels = -700.0, frameHeight = 640, response = 0.5)
    assertFalse("bez překryvu se díl neuloží", tracker.shouldCapture(receiptFound = true, sharp = true))
    assertEquals(CaptureHint.MOVE_BACK_UP, tracker.hint(receiptFound = true, sharp = true))
    // Couvnutí nahoru posun zase ubere.
    tracker.addFrameShift(dyPixels = 200.0, frameHeight = 640, response = 0.5)
    assertTrue(tracker.shouldCapture(receiptFound = true, sharp = true))
  }

  @Test
  fun slabaOdezvaAPosunPredPrvnimDilemSeNepocita() {
    val tracker = CoverageTracker()
    tracker.addFrameShift(dyPixels = -300.0, frameHeight = 640, response = 0.9)
    assertEquals(0.0, tracker.shiftSinceLastPart, 0.0)
    tracker.onPartSaved()
    tracker.addFrameShift(dyPixels = -300.0, frameHeight = 640, response = 0.01)
    assertEquals(0.0, tracker.shiftSinceLastPart, 0.0)
  }

  @Test
  fun posunNahoruNejdeDoZapornych() {
    val tracker = CoverageTracker()
    tracker.onPartSaved()
    tracker.addFrameShift(dyPixels = 300.0, frameHeight = 640, response = 0.9)
    assertEquals(0.0, tracker.shiftSinceLastPart, 0.0)
  }

  @Test
  fun limitDilu() {
    val tracker = CoverageTracker(maxParts = 2)
    tracker.onPartSaved()
    tracker.onPartSaved()
    tracker.addFrameShift(dyPixels = -400.0, frameHeight = 640, response = 0.9)
    assertFalse(tracker.shouldCapture(receiptFound = true, sharp = true))
    assertEquals(CaptureHint.LIMIT_REACHED, tracker.hint(receiptFound = true, sharp = true))
  }

  @Test
  fun pokynyPodleStavu() {
    val tracker = CoverageTracker()
    assertEquals(CaptureHint.FIND_RECEIPT, tracker.hint(receiptFound = false, sharp = true))
    assertEquals(CaptureHint.HOLD_STILL, tracker.hint(receiptFound = true, sharp = true))
    tracker.onPartSaved()
    assertEquals(CaptureHint.MOVE_DOWN, tracker.hint(receiptFound = true, sharp = true))
    assertEquals(CaptureHint.MOVE_DOWN, tracker.hint(receiptFound = false, sharp = true))
    tracker.reset()
    assertEquals(0, tracker.partCount)
  }

  @Test
  fun maskaZTazeniJakymkoliSmerem() {
    assertEquals(MaskRect(0.2f, 0.3f, 0.6f, 0.5f), maskFromDrag(0.6f, 0.5f, 0.2f, 0.3f))
    assertEquals(MaskRect(0f, 0.9f, 1f, 1f), maskFromDrag(-0.3f, 0.9f, 1.4f, 1.2f))
    assertNull("ťuknutí nic nezakryje", maskFromDrag(0.5f, 0.5f, 0.505f, 0.51f))
  }

  @Test
  fun maskaSeDoPixeluZaokrouhliVen() {
    val pixels = maskToPixels(MaskRect(0.101f, 0.749f, 0.5f, 1f), 1000, 2000)
    assertEquals(listOf(101, 1498, 500, 2000), pixels.toList())
  }

  @Test
  fun vychoziNavrhJeSpodniPas() {
    val mask = defaultMaskProposal()
    assertTrue(mask.contains(0.5f, 0.9f))
    assertFalse(mask.contains(0.5f, 0.5f))
  }

  @Test
  fun preskladaniDilu() {
    assertEquals(listOf("b", "a", "c"), listOf("a", "b", "c").moved(0, 1))
    assertEquals(listOf("c", "a", "b"), listOf("a", "b", "c").moved(2, 0))
    assertEquals(listOf("a", "b", "c"), listOf("a", "b", "c").moved(0, 5))
  }
}
