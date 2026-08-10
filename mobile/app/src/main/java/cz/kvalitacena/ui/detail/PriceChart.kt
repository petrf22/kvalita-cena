package cz.kvalitacena.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import cz.kvalitacena.network.PricePoint
import cz.kvalitacena.ui.common.rememberMoneyFormatter

/**
 * Graf vývoje ceny — vlastní vykreslení na Canvasu, žádná nová závislost (viz plán). Jedna
 * řada v barvě `colorScheme.primary`, schodová křivka, mřížka vlasová plná (ne čárkovaná),
 * popisky nikdy v barvě dat. Ceny se čtou z agg.price_daily (přes priceHistory), nikdy ze
 * syrových observací. `currency` z `PriceHistory.currency` (VŽDY vyplněná, docs/lokalizace.md)
 * — popisky pod grafem se skládají mimo `drawScope` (obyčejné `Text`, ne kreslený text na
 * Canvasu), takže si `rememberMoneyFormatter` klidně můžou zavolat samy.
 */
@Composable
fun PriceChart(points: List<PricePoint>, currency: String?, modifier: Modifier = Modifier) {
  if (points.isEmpty()) {
    Box(modifier = modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
      Text("Zatím nemáme dost hlášení na graf", style = MaterialTheme.typography.bodyMedium)
    }
    return
  }

  var selected by remember(points) { mutableStateOf<PricePoint?>(null) }
  val primaryColor = MaterialTheme.colorScheme.primary
  val gridColor = MaterialTheme.colorScheme.outlineVariant
  val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
  val surfaceColor = MaterialTheme.colorScheme.surface
  val padding = 24f

  Column(modifier = modifier) {
    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(220.dp)
        // Dotyková plocha je celý graf, ne malé puntíky — hledá se nejbližší bod podle X.
        .pointerInput(points) {
          detectTapGestures { offset ->
            val geometry = computeGeometry(points, size.width.toFloat(), size.height.toFloat(), padding)
            val nearestIndex = geometry.markers.indices.minByOrNull { kotlin.math.abs(geometry.markers[it].x - offset.x) }
            selected = nearestIndex?.let { points[it] }
          }
        },
    ) {
      val geometry = computeGeometry(points, size.width, size.height, padding)

      // Mřížka — vlasové PLNÉ čáry, nikdy čárkované (dataviz konvence projektu).
      val gridLines = 4
      for (i in 0..gridLines) {
        val y = padding + (size.height - 2 * padding) * i / gridLines
        drawLine(gridColor, Offset(padding, y), Offset(size.width - padding, y), strokeWidth = 1f)
      }

      if (geometry.markers.size == 1) {
        val y = geometry.markers[0].y
        drawLine(
          primaryColor,
          Offset(padding, y),
          Offset(size.width - padding, y),
          strokeWidth = 4f,
          cap = StrokeCap.Round,
        )
      } else if (geometry.stepPath.size >= 2) {
        val fillPath = Path().apply {
          moveTo(geometry.stepPath.first().x, size.height - padding)
          geometry.stepPath.forEach { lineTo(it.x, it.y) }
          lineTo(geometry.stepPath.last().x, size.height - padding)
          close()
        }
        drawPath(fillPath, color = primaryColor.copy(alpha = 0.1f))

        val linePath = Path().apply {
          moveTo(geometry.stepPath.first().x, geometry.stepPath.first().y)
          geometry.stepPath.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(
          linePath,
          color = primaryColor,
          style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
      }

      // Značky na skutečných dnech měření — bílý prstenec v barvě povrchu, ať vynikne nad čárou.
      geometry.markers.forEach { marker ->
        drawCircle(surfaceColor, radius = 6f, center = Offset(marker.x, marker.y))
        drawCircle(primaryColor, radius = 4f, center = Offset(marker.x, marker.y))
      }

      val selectedIndex = selected?.let { points.indexOf(it) } ?: -1
      if (selectedIndex in geometry.markers.indices) {
        val marker = geometry.markers[selectedIndex]
        drawLine(labelColor, Offset(marker.x, padding), Offset(marker.x, size.height - padding), strokeWidth = 1f)
      }
    }

    // Popisky nikdy v barvě dat (`labelColor` = onSurfaceVariant) — přímo se píše jen poslední
    // bod, extrém a vybraný bod; tabulka cen pod grafem zůstává hlavním zdrojem čísel.
    val moneyFormatter = rememberMoneyFormatter(currency)
    fun formatUnitPrice(value: Double) = "${moneyFormatter.format(value)}/jednotka"

    val last = points.last()
    Text(
      "Poslední: ${formatUnitPrice(last.unitPrice)} (${last.day})",
      style = MaterialTheme.typography.bodySmall,
      color = labelColor,
      modifier = Modifier.padding(top = 4.dp),
    )
    val min = points.minBy { it.unitPrice }
    val max = points.maxBy { it.unitPrice }
    Text(
      "Nejníž: ${formatUnitPrice(min.unitPrice)} (${min.day}) · Nejvýš: ${formatUnitPrice(max.unitPrice)} (${max.day})",
      style = MaterialTheme.typography.bodySmall,
      color = labelColor,
    )
    selected?.let { sel ->
      Text(
        "Vybráno: ${formatUnitPrice(sel.unitPrice)} · ${sel.day} · ${sel.nObs}× potvrzeno",
        style = MaterialTheme.typography.bodySmall,
        color = primaryColor,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}
