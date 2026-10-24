package cz.kvalitacena.ui.receipt.capture

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import cz.kvalitacena.crash.AppLog
import java.util.concurrent.atomic.AtomicReference
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Stav jednoho analyzovaného snímku pro overlay nad náhledem — [quad] je v souřadnicích snímku
 * [frameWidth] × [frameHeight] (na výšku, zmenšený na [CaptureThresholds.ANALYSIS_WIDTH]).
 */
data class FrameResult(
  val quad: Quad?,
  val frameWidth: Int,
  val frameHeight: Int,
  val sharpness: Double,
  val hint: CaptureHint,
  val progress: Float,
  val partCount: Int,
  /** Průběh odpočtu před prvním dílem (0–1); 0, dokud odpočet neběží ([startCountdownRunning]). */
  val startProgress: Float,
  val startCountdownRunning: Boolean,
)

/**
 * Živá analýza náhledu kamery (CameraX `ImageAnalysis`, formát YUV — jas je rovnou šedotón):
 * na zmenšeném snímku měří ostrost, hledá účtenku a posun proti předchozímu snímku, a když
 * [CoverageTracker] řekne, narovná účtenku z plného rozlišení a předá díl [onPart].
 *
 * Běží na vlastním vlákně analyzátoru; [tracker] se proto mění jen tady — změny zvenku
 * (smazaný díl v kontrole, nové snímání) chodí přes [resetParts] a použijí se na dalším snímku.
 */
class ReceiptFrameAnalyzer(
  private val onFrame: (FrameResult) -> Unit,
  private val onPart: (EncodedPart) -> Unit,
  initialParts: Int = 0,
  maxParts: Int = CaptureThresholds.MAX_PARTS,
  startDelayMillis: Long = CaptureThresholds.DEFAULT_START_DELAY_SECONDS * 1000L,
) : ImageAnalysis.Analyzer {

  private val tracker = CoverageTracker(maxParts = maxParts, startDelayMillis = startDelayMillis)
    .also { it.reset(initialParts) }

  /** Automatické ukládání vypnuté — ruční spoušť, nebo „nafotit znovu" jednoho dílu už hotové. */
  @Volatile
  var autoCapture: Boolean = true

  /**
   * Snímání dočasně stojí (přes náhled je otevřený návod) — nic se neuloží a odpočet před prvním
   * dílem se nerozběhne, ať se díl neuloží za dialogem, který uživatel zrovna čte.
   */
  @Volatile
  var paused: Boolean = false

  /**
   * Reset z hlavního vlákna pro vlákno analyzátoru. Atomické převzetí (`getAndSet`) — při
   * čtení a nulování zvlášť by se reset, který dorazí mezi nimi, ztratil a tracker by si
   * myslel, že má jiný počet dílů než koncept (třeba po smazání jediného dílu bez odpočtu).
   */
  private val pendingReset = AtomicReference<Int?>(null)

  private var yBytes = ByteArray(0)
  private var previous: Mat? = null
  private var window: Mat? = null

  /**
   * Počet dílů se změnil mimo analyzátor (ruční spoušť, import, smazání) — další automatický
   * díl se počítá od posledního uloženého, posun od nuly.
   */
  fun resetParts(parts: Int) {
    pendingReset.set(parts)
  }

  override fun analyze(image: ImageProxy) {
    try {
      if (ReceiptImageProcessor.ensureLoaded()) process(image)
    } catch (e: Exception) {
      // Jeden vadný snímek nesmí zastavit analýzu — další přijde za zlomek sekundy.
      AppLog.e("Analýza snímku účtenky selhala: ${e::class.simpleName}", e)
    } finally {
      image.close()
    }
  }

  private fun process(image: ImageProxy) {
    pendingReset.getAndSet(null)?.let { tracker.reset(it) }

    val sensorWidth = image.width
    val sensorHeight = image.height
    val rotation = image.imageInfo.rotationDegrees
    val sensor = luminance(image)
    val small: Mat
    try {
      val (uprightWidth, _) = uprightSize(sensorWidth, sensorHeight, rotation)
      val scale = CaptureThresholds.ANALYSIS_WIDTH.toDouble() / uprightWidth
      val smallSensor = if (rotation % 180 == 0) {
        ReceiptImageProcessor.resizeToWidth(sensor, CaptureThresholds.ANALYSIS_WIDTH)
      } else {
        // Otočený snímek: šířka na výšku je výška senzoru — zmenšuje se tak, aby po otočení
        // vyšla právě ANALYSIS_WIDTH.
        ReceiptImageProcessor.resizeToWidth(sensor, (sensorWidth * scale).toInt().coerceAtLeast(1))
      }
      small = ReceiptImageProcessor.rotateUpright(smallSensor, rotation)
      smallSensor.release()

      val pixels = ByteArray(small.cols() * small.rows())
      small.get(0, 0, pixels)
      val sharpness = laplacianVariance(pixels, small.cols(), small.rows())
      val sharp = isSharpEnough(sharpness)
      val quad = ReceiptImageProcessor.detectReceipt(small)
      trackShift(small)

      val found = quad != null
      if (paused) {
        tracker.cancelStart()
      } else {
        tracker.updateStart(quad, small.cols(), image.imageInfo.timestamp)
      }
      if (autoCapture && !paused && quad != null && tracker.shouldCapture(found, sharp)) {
        val fullQuad = quad.scaled(1.0 / scale)
        val (width, height) = straightenedSize(fullQuad)
        val sensorQuad = fullQuad.map { uprightToSensor(it, rotation, sensorWidth, sensorHeight) }
        val straight = ReceiptImageProcessor.straighten(sensor, sensorQuad, width, height)
        val part = try {
          // Okraj záběru protíná řádek textu napůl — worker by díly nespojil (snapToGap).
          val trimmed = ReceiptImageProcessor.trimPartialLines(straight)
          try {
            ReceiptImageProcessor.encodePart(trimmed)
          } finally {
            if (trimmed !== straight) trimmed.release()
          }
        } finally {
          straight.release()
        }
        tracker.onPartSaved()
        onPart(part)
      }

      onFrame(
        FrameResult(
          quad = quad,
          frameWidth = small.cols(),
          frameHeight = small.rows(),
          sharpness = sharpness,
          hint = tracker.hint(found, sharp),
          progress = tracker.progressToNextPart,
          partCount = tracker.partCount,
          startProgress = tracker.startProgress,
          startCountdownRunning = tracker.startCountdownRunning,
        ),
      )
      small.release()
    } finally {
      sensor.release()
    }
  }

  /** Jasová složka YUV snímku jako Mat v souřadnicích senzoru (kopie — buffer kamery se vrací). */
  private fun luminance(image: ImageProxy): Mat {
    val plane = image.planes[0]
    val buffer = plane.buffer
    buffer.rewind()
    val rowStride = plane.rowStride
    val needed = rowStride * image.height
    if (yBytes.size < needed) yBytes = ByteArray(needed)
    val available = minOf(buffer.remaining(), needed)
    buffer.get(yBytes, 0, available)
    val full = Mat(image.height, rowStride, CvType.CV_8UC1)
    full.put(0, 0, yBytes, 0, needed)
    val cropped = full.submat(0, image.height, 0, image.width).clone()
    full.release()
    return cropped
  }

  /**
   * Posun proti předchozímu snímku na polovičním rozlišení — `phaseCorrelate` je FFT přes celý
   * snímek a na posun o jednotky procent výšky čtvrtinový počet pixelů bohatě stačí.
   */
  private fun trackShift(small: Mat) {
    val half = ReceiptImageProcessor.resizeToWidth(small, small.cols() / 2)
    val current = Mat()
    half.convertTo(current, CvType.CV_32F)
    half.release()
    val prev = previous
    if (prev != null && prev.size() == current.size()) {
      val win = window?.takeIf { it.size() == current.size() }
        ?: ReceiptImageProcessor.hanningWindow(current.cols(), current.rows()).also {
          window?.release()
          window = it
        }
      val (dy, response) = ReceiptImageProcessor.verticalShift(prev, current, win)
      tracker.addFrameShift(dy, current.rows(), response)
    }
    prev?.release()
    previous = current
  }

  /** Uvolní nativní paměť, když obrazovka snímání končí. */
  fun release() {
    previous?.release()
    previous = null
    window?.release()
    window = null
  }
}
