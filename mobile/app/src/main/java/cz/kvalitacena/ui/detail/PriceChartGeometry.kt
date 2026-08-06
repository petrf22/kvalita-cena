package cz.kvalitacena.ui.detail

import cz.kvalitacena.network.PricePoint
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class ChartPoint(val x: Float, val y: Float)

data class ChartGeometry(
  /** Vrcholy schodové (step-after) křivky — cena platí, dokud ji někdo nezmění, žádná diagonála. */
  val stepPath: List<ChartPoint>,
  /** Body skutečných měření (dny, kdy někdo hlásil) — na ně se kreslí značky a hledá se k nim tap. */
  val markers: List<ChartPoint>,
  val minPrice: Float,
  val maxPrice: Float,
)

private val EMPTY_GEOMETRY = ChartGeometry(emptyList(), emptyList(), 0f, 0f)

/**
 * Čistá funkce bez Compose závislostí — testovatelná bez UI (viz PriceChartGeometryTest).
 * Osa X je skutečný čas (počet dní od prvního bodu), NE index bodu — v agg.price_daily jsou jen
 * dny, kdy někdo hlásil, indexová osa by z týdenní mezery udělala jeden krok a graf by lhal.
 */
fun computeGeometry(points: List<PricePoint>, width: Float, height: Float, padding: Float): ChartGeometry {
  if (points.isEmpty()) return EMPTY_GEOMETRY

  val days = points.map { LocalDate.parse(it.day) }
  val prices = points.map { it.unitPrice.toFloat() }

  val firstDay = days.first()
  val lastDay = days.last()
  val totalDaySpan = ChronoUnit.DAYS.between(firstDay, lastDay).coerceAtLeast(1).toFloat()

  var minPrice = prices.min()
  var maxPrice = prices.max()
  if (minPrice == maxPrice) {
    // Jediný bod nebo všechny hodnoty stejné — rozsah uměle roztáhnout o ±10 %, ať se nedělí nulou.
    val pad = if (minPrice == 0f) 1f else kotlin.math.abs(minPrice) * 0.1f
    minPrice -= pad
    maxPrice += pad
  }

  val plotWidth = (width - 2 * padding).coerceAtLeast(1f)
  val plotHeight = (height - 2 * padding).coerceAtLeast(1f)

  fun xFor(day: LocalDate): Float {
    if (points.size == 1) return padding + plotWidth / 2f
    val offset = ChronoUnit.DAYS.between(firstDay, day).toFloat()
    return padding + (offset / totalDaySpan) * plotWidth
  }

  fun yFor(price: Float): Float {
    val ratio = (price - minPrice) / (maxPrice - minPrice)
    return padding + (1f - ratio) * plotHeight
  }

  val markers = days.zip(prices).map { (day, price) -> ChartPoint(xFor(day), yFor(price)) }

  // Schodová (step-after) křivka: z bodu i vodorovně až pod bod i+1, pak svisle na jeho úroveň.
  val stepPath = mutableListOf<ChartPoint>()
  markers.forEachIndexed { index, point ->
    stepPath.add(point)
    if (index < markers.size - 1) {
      stepPath.add(ChartPoint(markers[index + 1].x, point.y))
    }
  }

  return ChartGeometry(stepPath, markers, minPrice, maxPrice)
}
