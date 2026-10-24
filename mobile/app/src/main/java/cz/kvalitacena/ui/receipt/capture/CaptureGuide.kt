package cz.kvalitacena.ui.receipt.capture

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Vodítko před prvním dílem: tři čáry ve tvaru obráceného U ukazují, kam patří horní okraj
 * účtenky a její boky (po stranách má zůstat vidět podložka — podle ní detekce papír najde),
 * a dokud účtenka v záběru není, telefon naznačí, kam se po startu snímání posouvat.
 *
 * Barva je bílá se stínem, ne z `MaterialTheme.colorScheme` — podklad je obraz z kamery,
 * ne povrch appky, a v obou režimech musí být čára vidět na tmavé podložce (docs/design.md).
 */
@Composable
fun StartGuideOverlay(receiptFound: Boolean, modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "receiptStartGuide")
  val cycle by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(GUIDE_CYCLE_MS, easing = LinearEasing), RepeatMode.Restart),
    label = "receiptStartGuideCycle",
  )

  Canvas(modifier) {
    val left = size.width * GUIDE_SIDE_RATIO
    val right = size.width * (1f - GUIDE_SIDE_RATIO)
    val top = size.height * GUIDE_TOP_RATIO
    val sideEnd = size.height * GUIDE_SIDE_END_RATIO
    // Nalezená účtenka má vlastní rámeček — vodítko pak jen ustoupí, ať se nepletou.
    val alpha = if (receiptFound) 0.45f else 0.95f
    drawGuideU(left, right, top, sideEnd, alpha)
    if (!receiptFound) drawMovingPhone(left, right, top, sideEnd, cycle)
  }
}

/** Obrácené U se zaoblenými rohy; boky dole vyblednou — účtenka pokračuje mimo záběr. */
private fun DrawScope.drawGuideU(left: Float, right: Float, top: Float, sideEnd: Float, alpha: Float) {
  val radius = 12.dp.toPx()
  val path = Path().apply {
    moveTo(left, sideEnd)
    lineTo(left, top + radius)
    quadraticTo(left, top, left + radius, top)
    lineTo(right - radius, top)
    quadraticTo(right, top, right, top + radius)
    lineTo(right, sideEnd)
  }
  fun fading(color: Color) = Brush.verticalGradient(
    0f to color,
    0.6f to color,
    1f to Color.Transparent,
    startY = top,
    endY = sideEnd,
  )
  val width = 4.dp.toPx()
  drawPath(
    path,
    fading(Color.Black.copy(alpha = SHADOW_ALPHA * alpha)),
    style = Stroke(width = width + 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
  )
  drawPath(
    path,
    fading(Color.White.copy(alpha = alpha)),
    style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
  )
}

/**
 * Obrys telefonu s šipkou, který v jednom cyklu [cycle] (0–1) vyjede od horní čáry dolů
 * a zmizí — jen naznačení směru, ne přesná rychlost.
 */
private fun DrawScope.drawMovingPhone(left: Float, right: Float, top: Float, sideEnd: Float, cycle: Float) {
  val phoneWidth = 34.dp.toPx()
  val phoneHeight = 58.dp.toPx()
  val startY = top + 20.dp.toPx()
  val endY = sideEnd - phoneHeight - 28.dp.toPx()
  if (endY <= startY) return

  val move = FastOutSlowInEasing.transform(((cycle - FADE_IN_END) / (MOVE_END - FADE_IN_END)).coerceIn(0f, 1f))
  val alpha = when {
    cycle < FADE_IN_END -> cycle / FADE_IN_END
    cycle < MOVE_END -> 1f
    else -> 1f - (cycle - MOVE_END) / (1f - MOVE_END)
  }.coerceIn(0f, 1f)
  if (alpha <= 0f) return

  val centerX = (left + right) / 2f
  val phoneTop = startY + (endY - startY) * move
  val stroke = 2.5.dp.toPx()
  val white = Color.White.copy(alpha = alpha)
  val shadow = Color.Black.copy(alpha = SHADOW_ALPHA * alpha)

  // Čára „snímání" přes šířku vodítka v úrovni telefonu — co právě zabírá kamera.
  val scanY = phoneTop + phoneHeight / 2f
  drawLine(white.copy(alpha = 0.35f * alpha), Offset(left + stroke * 4, scanY), Offset(right - stroke * 4, scanY), strokeWidth = stroke)

  val phoneLeft = centerX - phoneWidth / 2f
  for ((color, width) in listOf(shadow to stroke + 2.dp.toPx(), white to stroke)) {
    drawRoundRect(
      color,
      topLeft = Offset(phoneLeft, phoneTop),
      size = Size(phoneWidth, phoneHeight),
      cornerRadius = CornerRadius(6.dp.toPx()),
      style = Stroke(width = width),
    )
    // Reproduktor nahoře — ať je poznat, že jde o telefon a kde má horní stranu.
    val speakerY = phoneTop + 6.dp.toPx()
    drawLine(color, Offset(centerX - 5.dp.toPx(), speakerY), Offset(centerX + 5.dp.toPx(), speakerY), strokeWidth = width, cap = StrokeCap.Round)
    // Šipka dolů pod telefonem.
    val tipY = phoneTop + phoneHeight + 20.dp.toPx()
    val armY = tipY - 9.dp.toPx()
    val arrow = Path().apply {
      moveTo(centerX - 9.dp.toPx(), armY)
      lineTo(centerX, tipY)
      lineTo(centerX + 9.dp.toPx(), armY)
    }
    drawPath(arrow, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
  }
}

/** Okraj vodítka od boku náhledu — po stranách účtenky má zůstat vidět podložka. */
private const val GUIDE_SIDE_RATIO = 0.14f

/** Horní čára vodítka — pod pokynem a odpočtem, které jsou přes náhled nahoře. */
private const val GUIDE_TOP_RATIO = 0.22f

/** Kde boky vodítka vyblednou. */
private const val GUIDE_SIDE_END_RATIO = 0.82f

private const val GUIDE_CYCLE_MS = 2600
private const val FADE_IN_END = 0.15f
private const val MOVE_END = 0.8f
private const val SHADOW_ALPHA = 0.4f
