package cz.kvalitacena.ui.receipt.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageTest {

  private val quad = Quad(Pt(100.0, 50.0), Pt(380.0, 50.0), Pt(380.0, 600.0), Pt(100.0, 600.0))

  private fun ms(value: Long) = value * 1_000_000L

  private fun Quad.shiftedX(dx: Double) = map { Pt(it.x + dx, it.y) }

  @Test
  fun prvniDilAzPoOdpoctu() {
    val tracker = CoverageTracker(startDelayMillis = 2000)
    tracker.updateStart(quad, frameWidth = 480, timestampNanos = ms(0))
    assertTrue(tracker.startCountdownRunning)
    assertFalse("hned po nalezení se neukládá", tracker.shouldCapture(receiptFound = true, sharp = true))

    tracker.updateStart(quad, frameWidth = 480, timestampNanos = ms(1000))
    assertEquals(0.5f, tracker.startProgress, 1e-6f)
    assertFalse(tracker.shouldCapture(receiptFound = true, sharp = true))

    tracker.updateStart(quad, frameWidth = 480, timestampNanos = ms(2000))
    assertFalse("rozmazaný snímek ani po odpočtu", tracker.shouldCapture(receiptFound = true, sharp = false))
    assertTrue(tracker.shouldCapture(receiptFound = true, sharp = true))

    tracker.onPartSaved()
    assertFalse(tracker.startCountdownRunning)
    assertEquals(0f, tracker.startProgress, 0f)
  }

  @Test
  fun odpocetZacneZnovuKdyzRamecekUjede() {
    val tracker = CoverageTracker(startDelayMillis = 2000)
    tracker.updateStart(quad, frameWidth = 480, timestampNanos = ms(0))
    // Třesoucí se ruka: 10 px z 480 je pod 6 % — odpočet běží dál.
    tracker.updateStart(quad.shiftedX(10.0), frameWidth = 480, timestampNanos = ms(1500))
    assertEquals(0.75f, tracker.startProgress, 1e-6f)
    // Uživatel ještě míří: 40 px je přes 6 % šířky záběru.
    tracker.updateStart(quad.shiftedX(40.0), frameWidth = 480, timestampNanos = ms(1600))
    assertEquals(0f, tracker.startProgress, 0f)
    tracker.updateStart(quad.shiftedX(40.0), frameWidth = 480, timestampNanos = ms(3600))
    assertTrue(tracker.shouldCapture(receiptFound = true, sharp = true))
  }

  @Test
  fun kratkyVypadekDetekceOdpocetNeshodi() {
    val tracker = CoverageTracker(startDelayMillis = 2000)
    tracker.updateStart(quad, frameWidth = 480, timestampNanos = ms(0))
    tracker.updateStart(null, frameWidth = 480, timestampNanos = ms(200))
    assertTrue("jeden snímek bez rámečku", tracker.startCountdownRunning)
    tracker.updateStart(quad, frameWidth = 480, timestampNanos = ms(400))
    assertEquals(0.2f, tracker.startProgress, 1e-6f)

    tracker.updateStart(null, frameWidth = 480, timestampNanos = ms(800))
    assertFalse("účtenka zmizela na déle než 300 ms", tracker.startCountdownRunning)
    tracker.updateStart(quad, frameWidth = 480, timestampNanos = ms(900))
    assertEquals(0f, tracker.startProgress, 0f)
  }

  @Test
  fun pozastaveniAResetOdpocetZrusi() {
    val tracker = CoverageTracker(startDelayMillis = 2000)
    tracker.updateStart(quad, frameWidth = 480, timestampNanos = ms(0))
    tracker.cancelStart()
    assertFalse(tracker.startCountdownRunning)

    tracker.updateStart(quad, frameWidth = 480, timestampNanos = ms(100))
    tracker.reset()
    assertFalse(tracker.startCountdownRunning)
  }

  @Test
  fun dalsiDilyOdpocetNemaji() {
    val tracker = CoverageTracker(startDelayMillis = 2000)
    tracker.onPartSaved()
    tracker.updateStart(quad, frameWidth = 480, timestampNanos = ms(0))
    assertFalse(tracker.startCountdownRunning)
    tracker.addFrameShift(dyPixels = -400.0, frameHeight = 640, response = 0.5)
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
    assertEquals(CaptureHint.HOLD_STILL, tracker.hint(receiptFound = true, sharp = false))
    assertEquals(CaptureHint.STARTING, tracker.hint(receiptFound = true, sharp = true))
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
