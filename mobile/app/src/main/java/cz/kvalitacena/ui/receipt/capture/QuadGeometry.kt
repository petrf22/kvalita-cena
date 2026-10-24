package cz.kvalitacena.ui.receipt.capture

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Geometrie snímání účtenky bez OpenCV — co jde spočítat z čísel, žije tady a má JUnit testy
 * (`QuadGeometryTest`); `ReceiptImageProcessor` jen dodá body z `findContours`/`approxPolyDP`
 * a výsledek použije pro `warpPerspective`.
 */
data class Pt(val x: Double, val y: Double) {
  fun scaled(factor: Double) = Pt(x * factor, y * factor)
}

/** Čtyřúhelník účtenky v pořadí levý horní, pravý horní, pravý dolní, levý dolní. */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
  val points: List<Pt> get() = listOf(tl, tr, br, bl)

  fun scaled(factor: Double) = Quad(tl.scaled(factor), tr.scaled(factor), br.scaled(factor), bl.scaled(factor))

  fun map(transform: (Pt) -> Pt) = Quad(transform(tl), transform(tr), transform(br), transform(bl))
}

/**
 * Seřadí čtyři body do [Quad]. Klasický trik: levý horní má nejmenší součet x + y, pravý
 * dolní největší, pravý horní nejmenší rozdíl y − x a levý dolní největší. Funguje pro
 * účtenku natočenou do ±45°, víc se při focení nestává.
 */
fun orderCorners(points: List<Pt>): Quad {
  require(points.size == 4) { "Čtyřúhelník potřebuje čtyři body, ne ${points.size}" }
  val bySum = points.sortedBy { it.x + it.y }
  val byDiff = points.sortedBy { it.y - it.x }
  return Quad(tl = bySum.first(), tr = byDiff.first(), br = bySum.last(), bl = byDiff.last())
}

/** Plocha mnohoúhelníku (shoelace), vždy kladná. */
fun polygonArea(points: List<Pt>): Double {
  var sum = 0.0
  for (i in points.indices) {
    val a = points[i]
    val b = points[(i + 1) % points.size]
    sum += a.x * b.y - b.x * a.y
  }
  return abs(sum) / 2.0
}

/** Konvexní čtyřúhelník — všechny vektorové součiny sousedních hran mají stejné znaménko. */
fun isConvex(quad: Quad): Boolean {
  val p = quad.points
  var sign = 0
  for (i in p.indices) {
    val a = p[i]
    val b = p[(i + 1) % 4]
    val c = p[(i + 2) % 4]
    val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
    if (cross == 0.0) return false
    val s = if (cross > 0) 1 else -1
    if (sign == 0) sign = s else if (s != sign) return false
  }
  return true
}

/**
 * Je čtyřúhelník věrohodná účtenka v záběru [frameWidth] × [frameHeight]? Musí být
 * konvexní, zabírat aspoň [CaptureThresholds.MIN_QUAD_AREA_RATIO] plochy a žádná strana nesmí
 * být zanedbatelně krátká (degenerovaný trojúhelník z `approxPolyDP`).
 */
fun isPlausibleReceiptQuad(quad: Quad, frameWidth: Int, frameHeight: Int): Boolean {
  if (!isConvex(quad)) return false
  val frameArea = frameWidth.toDouble() * frameHeight
  if (polygonArea(quad.points) < CaptureThresholds.MIN_QUAD_AREA_RATIO * frameArea) return false
  val minSide = 0.05 * min(frameWidth, frameHeight)
  val p = quad.points
  return p.indices.all { i -> distance(p[i], p[(i + 1) % 4]) >= minSide }
}

private fun distance(a: Pt, b: Pt): Double = hypot(a.x - b.x, a.y - b.y)

/**
 * Největší posun kteréhokoli rohu mezi dvěma čtyřúhelníky, v pixelech — podle něj odpočet
 * před prvním dílem pozná, že uživatel ještě míří ([CaptureThresholds.START_MAX_DRIFT_RATIO]).
 */
fun maxCornerDrift(a: Quad, b: Quad): Double = a.points.zip(b.points).maxOf { (p, q) -> distance(p, q) }

/**
 * Rozměr narovnaného výřezu — delší z protějších stran, ať narovnání text nestlačí. Výsledek
 * je v pixelech zdroje, ze kterého čtyřúhelník pochází.
 */
fun straightenedSize(quad: Quad): Pair<Int, Int> {
  val width = max(distance(quad.tl, quad.tr), distance(quad.bl, quad.br))
  val height = max(distance(quad.tl, quad.bl), distance(quad.tr, quad.br))
  return max(1, width.roundToInt()) to max(1, height.roundToInt())
}

/**
 * Zmenší rozměr na šířku nejvýš [maxWidth] se zachovaným poměrem stran; menší obrázek se
 * nezvětšuje (zvětšení text nezlepší, jen zvětší soubor).
 */
fun limitWidth(width: Int, height: Int, maxWidth: Int = CaptureThresholds.OUTPUT_MAX_WIDTH): Pair<Int, Int> {
  if (width <= maxWidth) return width to height
  val factor = maxWidth.toDouble() / width
  return maxWidth to max(1, (height * factor).roundToInt())
}

/**
 * Světlý pruh papíru v profilu jasu sloupců (průměr sloupce 0–255) — záchranná detekce dlouhé
 * účtenky, která přesahuje záběr nahoře i dole a uzavřenou konturu tak nemá. Levá hrana je
 * nejstrmější nárůst jasu v levé polovině, pravá nejstrmější pokles v pravé; pruh platí, jen
 * když je dost široký a papír je proti okolí dost světlý.
 *
 * Vrací rozsah sloupců papíru (včetně), nebo `null`.
 */
fun findBrightBand(
  profile: DoubleArray,
  minWidthRatio: Double = CaptureThresholds.MIN_BAND_WIDTH_RATIO,
  minContrast: Double = CaptureThresholds.MIN_BAND_CONTRAST,
): IntRange? {
  val n = profile.size
  if (n < 8) return null
  val half = n / 2
  var left = -1
  var leftStep = 0.0
  for (x in 1..half) {
    val step = profile[x] - profile[x - 1]
    if (step > leftStep) {
      leftStep = step
      left = x
    }
  }
  var right = -1
  var rightStep = 0.0
  for (x in half until n - 1) {
    val step = profile[x] - profile[x + 1]
    if (step > rightStep) {
      rightStep = step
      right = x
    }
  }
  // Papír až k okraji záběru: hrana na té straně není vidět, pruh začíná/končí okrajem.
  if (left < 0) left = 0
  if (right < 0) right = n - 1
  if (right - left + 1 < minWidthRatio * n) return null

  val inside = profile.sliceArray(left..right).average()
  val outsideValues = profile.sliceArray(0 until left) + profile.sliceArray(right + 1 until n)
  // Papír přes celou šířku záběru nemá s čím kontrastovat — to je stav „jsem moc blízko",
  // ne nalezená účtenka.
  if (outsideValues.isEmpty()) return null
  if (inside - outsideValues.average() < minContrast) return null
  return left..right
}

/**
 * Čtyřúhelník dlouhé účtenky z bočních hran nalezených zvlášť v horním a dolním pásu záběru
 * ([topBand] kolem řádku [topY], [bottomBand] kolem řádku [bottomY]) — hrany se protáhnou na
 * celou výšku záběru, takže mírně natočená účtenka se narovná i bez viditelného horního
 * a dolního okraje.
 */
fun quadFromSideEdges(topBand: IntRange, bottomBand: IntRange, topY: Double, bottomY: Double, frameHeight: Int): Quad {
  require(bottomY > topY) { "Dolní pás musí být pod horním" }
  fun extrapolate(xTop: Double, xBottom: Double, y: Double): Double =
    xTop + (xBottom - xTop) * (y - topY) / (bottomY - topY)

  val h = frameHeight.toDouble()
  val leftTop = topBand.first.toDouble()
  val leftBottom = bottomBand.first.toDouble()
  val rightTop = topBand.last.toDouble()
  val rightBottom = bottomBand.last.toDouble()
  return Quad(
    tl = Pt(extrapolate(leftTop, leftBottom, 0.0), 0.0),
    tr = Pt(extrapolate(rightTop, rightBottom, 0.0), 0.0),
    br = Pt(extrapolate(rightTop, rightBottom, h), h),
    bl = Pt(extrapolate(leftTop, leftBottom, h), h),
  )
}

/**
 * Bod ze snímku otočeného do polohy „na výšku" ([rotationDegrees] z `ImageInfo`, tedy o kolik
 * stupňů po směru hodin se musí snímek senzoru otočit) zpět do souřadnic senzoru
 * [sensorWidth] × [sensorHeight]. Díky tomu se `warpPerspective` počítá rovnou z bufferu kamery
 * a plnou velikost snímku není potřeba nejdřív otáčet.
 */
fun uprightToSensor(p: Pt, rotationDegrees: Int, sensorWidth: Int, sensorHeight: Int): Pt =
  when (((rotationDegrees % 360) + 360) % 360) {
    0 -> p
    90 -> Pt(p.y, sensorHeight - p.x)
    180 -> Pt(sensorWidth - p.x, sensorHeight - p.y)
    270 -> Pt(sensorWidth - p.y, p.x)
    else -> throw IllegalArgumentException("Nepodporované otočení $rotationDegrees°")
  }

/** Rozměr snímku po otočení do polohy „na výšku". */
fun uprightSize(sensorWidth: Int, sensorHeight: Int, rotationDegrees: Int): Pair<Int, Int> =
  if (rotationDegrees % 180 == 0) sensorWidth to sensorHeight else sensorHeight to sensorWidth

/**
 * Bod snímku [imageWidth] × [imageHeight] v souřadnicích náhledu [viewWidth] × [viewHeight],
 * který obraz vyplňuje a přečnívající část ořízne na střed (`PreviewView.ScaleType.FILL_CENTER`)
 * — pro rámeček nalezené účtenky přes náhled kamery.
 */
fun imageToFillCenterView(p: Pt, imageWidth: Int, imageHeight: Int, viewWidth: Double, viewHeight: Double): Pt {
  val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
  val offsetX = (viewWidth - imageWidth * scale) / 2.0
  val offsetY = (viewHeight - imageHeight * scale) / 2.0
  return Pt(p.x * scale + offsetX, p.y * scale + offsetY)
}

/**
 * Rozřezání vysokého obrázku (import z galerie — sken nebo dlouhá fotka) na díly výšky
 * [tileHeight] s překryvem aspoň [minOverlapRatio], tak jak je posílá snímání kamerou: dlouhá
 * účtenka jako jeden obrázek OCR modelu padala (docs/ai.md, „Účtenky"). Díly se rozloží
 * rovnoměrně od horního k dolnímu okraji, ať na konci nevznikne tenký proužek.
 */
fun tileRanges(
  height: Int,
  tileHeight: Int,
  minOverlapRatio: Double = 1.0 - CaptureThresholds.NEW_PART_SHIFT_RATIO,
): List<IntRange> {
  require(height > 0 && tileHeight > 0) { "Rozměry musí být kladné" }
  require(minOverlapRatio in 0.0..<1.0) { "Překryv musí být v [0, 1)" }
  if (height <= tileHeight) return listOf(0 until height)
  val maxStep = tileHeight * (1.0 - minOverlapRatio)
  val steps = kotlin.math.ceil((height - tileHeight) / maxStep).toInt()
  val step = (height - tileHeight).toDouble() / steps
  return (0..steps).map { i ->
    val top = if (i == steps) height - tileHeight else (i * step).roundToInt()
    top until top + tileHeight
  }
}

/**
 * Řádek obrázku nejblíž [target] (v okně ±[window]), kde je nejméně „inkoustu" ([rowInk] =
 * počet tmavých pixelů v řádku) — tedy mezera mezi řádky textu. Worker spojuje díly podle
 * řádků zopakovaných v překryvu a vyžaduje přesnou shodu (`tools/uctenky/parse.py`,
 * `merge_segments`); řez vedený napříč řádkem textu by z něj na obou stranách udělal
 * nečitelný zbytek a překryv by se nespojil (součet účtenky by nesedl).
 */
fun snapToGap(rowInk: IntArray, target: Int, window: Int): Int {
  if (rowInk.isEmpty()) return target
  val from = (target - window).coerceIn(0, rowInk.lastIndex)
  val to = (target + window).coerceIn(0, rowInk.lastIndex)
  var best = target.coerceIn(from, to)
  for (row in from..to) {
    val better = rowInk[row] < rowInk[best] ||
      (rowInk[row] == rowInk[best] && kotlin.math.abs(row - target) < kotlin.math.abs(best - target))
    if (better) best = row
  }
  return best
}

/**
 * [tileRanges] s řezy posunutými do mezer mezi řádky textu ([snapToGap]). Horní okraj
 * prvního a dolní okraj posledního dílu zůstávají na okraji obrázku.
 */
fun gapAlignedTileRanges(
  rowInk: IntArray,
  tileHeight: Int,
  windowRatio: Double = CaptureThresholds.CUT_SNAP_WINDOW_RATIO,
): List<IntRange> {
  val window = (tileHeight * windowRatio).roundToInt()
  val ranges = tileRanges(rowInk.size, tileHeight)
  return ranges.mapIndexed { index, range ->
    val top = if (index == 0) range.first else snapToGap(rowInk, range.first, window)
    val bottom = if (index == ranges.lastIndex) range.last else snapToGap(rowInk, range.last, window)
    top..maxOf(top, bottom)
  }
}

/**
 * Oříznutí dílu z kamery na celé řádky textu — horní a dolní okraj záběru obvykle protíná řádek
 * napůl a takový zbytek by spojení dílů ve workeru rozbil (viz [snapToGap]). Vrací rozsah
 * řádků, který se má ponechat.
 */
fun trimToTextGaps(rowInk: IntArray, windowRatio: Double = CaptureThresholds.CUT_SNAP_WINDOW_RATIO): IntRange {
  if (rowInk.isEmpty()) return IntRange.EMPTY
  val window = (rowInk.size * windowRatio).roundToInt()
  val top = snapToGap(rowInk, 0, window)
  val bottom = snapToGap(rowInk, rowInk.lastIndex, window)
  return if (bottom > top) top..bottom else rowInk.indices
}
