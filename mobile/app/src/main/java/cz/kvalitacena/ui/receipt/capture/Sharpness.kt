package cz.kvalitacena.ui.receipt.capture

/**
 * Ostrost snímku jako rozptyl Laplacianu (docs/ai.md, „Snímání na klientovi") — rozmazaný
 * snímek nemá ostré přechody, Laplacian je na něm všude blízko nule a rozptyl malý.
 *
 * Záměrně v čistém Kotlinu, ne přes `Imgproc.Laplacian`: jádro 3 × 3 (0 1 0 / 1 −4 1 / 0 1 0)
 * je totéž, co počítá `cv2.Laplacian(gray, CV_64F)` s výchozím `ksize=1`, ze kterého práh
 * pochází, na snímku šířky 480 px je to zlomek milisekundy a hlavně jde otestovat bez
 * nativní knihovny (`SharpnessTest`).
 *
 * [gray] je 8bitový šedotón po řádcích, [rowStride] délka řádku v bajtech (u výřezu z většího
 * bufferu větší než [width]). Okrajové pixely se vynechávají, stejně jako by je jádro
 * s výplní hranou nijak nezměnilo.
 */
fun laplacianVariance(gray: ByteArray, width: Int, height: Int, rowStride: Int = width): Double {
  require(width > 0 && height > 0) { "Prázdný snímek" }
  require(rowStride >= width && gray.size >= rowStride * (height - 1) + width) { "Buffer je menší než snímek" }
  if (width < 3 || height < 3) return 0.0

  var sum = 0.0
  var sumSquares = 0.0
  var count = 0
  for (y in 1 until height - 1) {
    val row = y * rowStride
    for (x in 1 until width - 1) {
      val i = row + x
      val center = gray[i].toInt() and 0xFF
      val value = (gray[i - 1].toInt() and 0xFF) + (gray[i + 1].toInt() and 0xFF) +
        (gray[i - rowStride].toInt() and 0xFF) + (gray[i + rowStride].toInt() and 0xFF) - 4 * center
      sum += value
      sumSquares += value.toDouble() * value
      count++
    }
  }
  val mean = sum / count
  return sumSquares / count - mean * mean
}

/** Je snímek dost ostrý na uložení výřezu (práh `CaptureThresholds.MIN_SHARPNESS`)? */
fun isSharpEnough(variance: Double): Boolean = variance >= CaptureThresholds.MIN_SHARPNESS
