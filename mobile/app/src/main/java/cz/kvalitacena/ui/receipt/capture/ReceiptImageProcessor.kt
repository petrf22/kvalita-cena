package cz.kvalitacena.ui.receipt.capture

import cz.kvalitacena.crash.AppLog
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/** Výřez účtenky připravený k uložení — šedotónový JPEG a jeho rozměr (pro poměr stran v kontrole). */
class EncodedPart(val jpeg: ByteArray, val width: Int, val height: Int)

/**
 * Práce s obrazem přes OpenCV (docs/rozvoj.md, „Sběr snímků na klientovi"): hledání účtenky
 * (Canny + kontury + `approxPolyDP`, se záchranou podle bočních hran), posun mezi snímky
 * (`phaseCorrelate`), narovnání (`warpPerspective`) a JPEG. Geometrie, která jde spočítat
 * bez nativní knihovny, je v `QuadGeometry.kt` s testy — tady jen volání OpenCV kolem ní.
 *
 * Žádné rozpoznávání textu: appka posílá malé narovnané šedotónové výřezy, číst je bude worker
 * (docs/ai.md, „Účtenky").
 */
object ReceiptImageProcessor {

  private val loaded: Boolean by lazy {
    runCatching { OpenCVLoader.initLocal() }
      .onFailure { AppLog.e("OpenCV se nepodařilo načíst", it) }
      .getOrDefault(false)
  }

  /**
   * Načte nativní knihovnu (jednou za proces). `false` = zařízení bez podporovaného ABI —
   * snímání pak jede jen ruční spouští bez detekce, výřez je celý snímek.
   */
  fun ensureLoaded(): Boolean = loaded

  /**
   * Najde účtenku na zmenšeném šedotónovém snímku ([small], CV_8UC1 na výšku). Vrací
   * čtyřúhelník v souřadnicích [small], nebo `null`.
   */
  fun detectReceipt(small: Mat): Quad? {
    val blurred = Mat()
    val edges = Mat()
    val hierarchy = Mat()
    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
    val contours = ArrayList<MatOfPoint>()
    try {
      Imgproc.GaussianBlur(small, blurred, Size(5.0, 5.0), 0.0)
      Imgproc.Canny(blurred, edges, 50.0, 150.0)
      // Přerušená hrana papíru (odlesk, stín) by konturu rozpojila.
      Imgproc.dilate(edges, edges, kernel)
      Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
      val candidates = contours.sortedByDescending { Imgproc.contourArea(it) }.take(5)
      for (contour in candidates) {
        val quad = approximateQuad(contour) ?: continue
        if (isPlausibleReceiptQuad(quad, small.cols(), small.rows())) return quad
      }
      return detectBySideEdges(blurred)
    } finally {
      contours.forEach { it.release() }
      blurred.release()
      edges.release()
      hierarchy.release()
      kernel.release()
    }
  }

  private fun approximateQuad(contour: MatOfPoint): Quad? {
    val curve = MatOfPoint2f(*contour.toArray())
    val approx = MatOfPoint2f()
    try {
      val perimeter = Imgproc.arcLength(curve, true)
      Imgproc.approxPolyDP(curve, approx, 0.02 * perimeter, true)
      if (approx.total() != 4L) return null
      return orderCorners(approx.toArray().map { Pt(it.x, it.y) })
    } finally {
      curve.release()
      approx.release()
    }
  }

  /**
   * Záchrana pro dlouhou účtenku zblízka: horní ani dolní okraj není v záběru, uzavřená kontura
   * tedy neexistuje — papír se pozná jako světlý svislý pruh v horní a dolní třetině záběru.
   */
  private fun detectBySideEdges(blurred: Mat): Quad? {
    val width = blurred.cols()
    val height = blurred.rows()
    val bandHeight = height / 3
    if (bandHeight < 4) return null
    val topBand = findBrightBand(columnMeans(blurred.submat(0, bandHeight, 0, width))) ?: return null
    val bottomBand = findBrightBand(columnMeans(blurred.submat(height - bandHeight, height, 0, width))) ?: return null
    val quad = quadFromSideEdges(
      topBand,
      bottomBand,
      topY = bandHeight / 2.0,
      bottomY = height - bandHeight / 2.0,
      frameHeight = height,
    )
    return quad.takeIf { isPlausibleReceiptQuad(it, width, height) }
  }

  private fun columnMeans(region: Mat): DoubleArray {
    val reduced = Mat()
    try {
      Core.reduce(region, reduced, 0, Core.REDUCE_AVG, CvType.CV_64F)
      val values = DoubleArray(reduced.cols())
      reduced.get(0, 0, values)
      return values
    } finally {
      reduced.release()
      region.release()
    }
  }

  /** Zmenší šedotón na šířku [width] (INTER_AREA — při zmenšování nevyrábí moaré z textu). */
  fun resizeToWidth(src: Mat, width: Int): Mat {
    val dst = Mat()
    val height = (src.rows() * width.toDouble() / src.cols()).roundToInt().coerceAtLeast(1)
    Imgproc.resize(src, dst, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
    return dst
  }

  /** Otočí snímek senzoru do polohy „na výšku" (`ImageInfo.rotationDegrees`). */
  fun rotateUpright(src: Mat, rotationDegrees: Int): Mat {
    val code = when (((rotationDegrees % 360) + 360) % 360) {
      90 -> Core.ROTATE_90_CLOCKWISE
      180 -> Core.ROTATE_180
      270 -> Core.ROTATE_90_COUNTERCLOCKWISE
      else -> return src.clone()
    }
    val dst = Mat()
    Core.rotate(src, dst, code)
    return dst
  }

  /**
   * Svislý posun obsahu mezi dvěma snímky stejné velikosti (CV_32F) v pixelech a odezva
   * korelace (0–1). Hannovo okno potlačí falešný vrchol od okrajů snímku.
   */
  fun verticalShift(previous: Mat, current: Mat, window: Mat): Pair<Double, Double> {
    val response = DoubleArray(1)
    val shift = Imgproc.phaseCorrelate(previous, current, window, response)
    return shift.y to response[0]
  }

  fun hanningWindow(width: Int, height: Int): Mat {
    val window = Mat()
    Imgproc.createHanningWindow(window, Size(width.toDouble(), height.toDouble()), CvType.CV_32F)
    return window
  }

  /**
   * Narovná čtyřúhelník [sourceQuad] (v souřadnicích [src]) na obdélník [width] × [height].
   * Rohy mimo snímek (záchranná detekce dlouhé účtenky) doplní opakování okrajového pixelu.
   */
  fun straighten(src: Mat, sourceQuad: Quad, width: Int, height: Int): Mat {
    val sourcePoints = MatOfPoint2f(*sourceQuad.points.map { Point(it.x, it.y) }.toTypedArray())
    val w = (width - 1).toDouble()
    val h = (height - 1).toDouble()
    val targetPoints = MatOfPoint2f(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h))
    val transform = Imgproc.getPerspectiveTransform(sourcePoints, targetPoints)
    val dst = Mat()
    try {
      Imgproc.warpPerspective(
        src,
        dst,
        transform,
        Size(width.toDouble(), height.toDouble()),
        Imgproc.INTER_LINEAR,
        Core.BORDER_REPLICATE,
      )
      return dst
    } finally {
      sourcePoints.release()
      targetPoints.release()
      transform.release()
    }
  }

  /** Šedotón → JPEG šířky nejvýš [CaptureThresholds.OUTPUT_MAX_WIDTH] (docs/ai.md, `max-width`). */
  fun encodePart(gray: Mat): EncodedPart {
    val (width, height) = limitWidth(gray.cols(), gray.rows())
    val resized = if (width == gray.cols()) gray else resizeToWidth(gray, width)
    val buffer = MatOfByte()
    val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, CaptureThresholds.JPEG_QUALITY)
    try {
      check(Imgcodecs.imencode(".jpg", resized, buffer, params)) { "JPEG se nepodařilo zakódovat" }
      return EncodedPart(buffer.toArray(), resized.cols(), resized.rows())
    } finally {
      if (resized !== gray) resized.release()
      buffer.release()
      params.release()
    }
  }

  /**
   * Jeden snímek z ruční spouště nebo z galerie ([encodedImage], JPEG/PNG) → díly k uložení.
   * Najde-li účtenku, narovná ji; jinak použije celý snímek — ruční spoušť má fungovat vždy,
   * i když detekce nic nenajde (docs/rozvoj.md, „ruční spoušť jako záloha").
   *
   * [rotationDegrees] je otočení snímku z kamery (`ImageInfo`), u galerie `null` — tam se
   * řídí EXIF orientací souboru. [splitTall] rozřeže dlouhý obrázek (sken celé účtenky) na
   * díly s překryvem, jak by je poslalo snímání kamerou.
   */
  fun processStill(encodedImage: ByteArray, rotationDegrees: Int?, splitTall: Boolean): List<EncodedPart> {
    val buffer = MatOfByte(*encodedImage)
    val flags = if (rotationDegrees == null) {
      Imgcodecs.IMREAD_GRAYSCALE
    } else {
      Imgcodecs.IMREAD_GRAYSCALE or Imgcodecs.IMREAD_IGNORE_ORIENTATION
    }
    val decoded = Imgcodecs.imdecode(buffer, flags)
    buffer.release()
    check(!decoded.empty()) { "Obrázek se nepodařilo přečíst" }
    val upright = rotateUpright(decoded, rotationDegrees ?: 0)
    decoded.release()
    try {
      val straightened = straightenWhole(upright)
      try {
        if (!splitTall) return listOf(encodePart(straightened))
        val tileHeight = (straightened.cols() * CaptureThresholds.IMPORT_TILE_ASPECT).roundToInt()
        return tileRanges(straightened.rows(), tileHeight).map { range ->
          val tile = straightened.submat(range.first, range.last + 1, 0, straightened.cols())
          try {
            encodePart(tile)
          } finally {
            tile.release()
          }
        }
      } finally {
        if (straightened !== upright) straightened.release()
      }
    } finally {
      upright.release()
    }
  }

  /** Najde a narovná účtenku na celém snímku; když ji nenajde, vrátí snímek beze změny. */
  private fun straightenWhole(upright: Mat): Mat {
    val scale = CaptureThresholds.ANALYSIS_WIDTH.toDouble() / upright.cols()
    if (scale >= 1.0) return upright
    val small = resizeToWidth(upright, CaptureThresholds.ANALYSIS_WIDTH)
    val quad = try {
      detectReceipt(small)
    } finally {
      small.release()
    }
    quad ?: return upright
    val fullQuad = quad.scaled(1.0 / scale)
    val (width, height) = straightenedSize(fullQuad)
    return straighten(upright, fullQuad, width, height)
  }

  /**
   * Vypálí masky do pixelů dílu (docs/soukromi.md, „Účtenky": zakrytý obsah na server vůbec
   * nedorazí) — plné černé obdélníky přímo v obrázku, ne vrstva navíc.
   */
  fun burnMasks(jpeg: ByteArray, masks: List<MaskRect>): ByteArray {
    if (masks.isEmpty()) return jpeg
    val buffer = MatOfByte(*jpeg)
    val gray = Imgcodecs.imdecode(buffer, Imgcodecs.IMREAD_GRAYSCALE)
    buffer.release()
    check(!gray.empty()) { "Díl účtenky se nepodařilo přečíst" }
    try {
      masks.forEach { mask ->
        val (x0, y0, x1, y1) = maskToPixels(mask, gray.cols(), gray.rows()).toList()
        if (x1 > x0 && y1 > y0) {
          Imgproc.rectangle(gray, Point(x0.toDouble(), y0.toDouble()), Point((x1 - 1).toDouble(), (y1 - 1).toDouble()), Scalar(0.0), -1)
        }
      }
      return encodePart(gray).jpeg
    } finally {
      gray.release()
    }
  }
}
