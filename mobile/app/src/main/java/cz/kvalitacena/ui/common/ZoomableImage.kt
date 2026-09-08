package cz.kvalitacena.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.AsyncImage
import kotlin.math.max
import kotlin.math.min

/** Meze zvětšení — pod 1× by fotka plavala v prázdnu, nad 5× už jsou vidět jen pixely. */
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f

/** Na kolik zvětší dvojklik. Míň než [MAX_SCALE], ať zbyde kam přiblížit prsty. */
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * Obrázek, se kterým jde pracovat jako s fotkou — roztažení dvěma prsty zvětší, tažení posouvá,
 * dvojklik přepíná mezi celou fotkou a přiblížením. Ruční implementace bez další knihovny,
 * stejný duch jako ruční Canvas graf (ui/detail/PriceChart.kt).
 *
 * [onZoomedChange] hlásí ven, jestli je fotka zvětšená — prohlížeč podle toho vypne listování
 * stránek (`HorizontalPager(userScrollEnabled = ...)` v PhotoGallery.kt), jinak by tažení
 * zvětšené fotky přeplo na další místo aby posunulo výřez.
 */
@Composable
fun ZoomableImage(
  model: Any?,
  contentDescription: String?,
  onZoomedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  var scale by remember(model) { mutableFloatStateOf(MIN_SCALE) }
  var offset by remember(model) { mutableStateOf(Offset.Zero) }
  // Skutečný poměr stran obrázku; ContentScale.Fit nechává po stranách prázdno, takže bez něj
  // by se clamp počítal z kontejneru a širokou fotku by šlo vytáhnout mimo obraz. Do prvního
  // načtení (i když se načíst nepovede) se počítá s kontejnerem — do té doby není co posouvat.
  var intrinsicSize by remember(model) { mutableStateOf(Size.Unspecified) }

  LaunchedEffect(scale) { onZoomedChange(scale > MIN_SCALE) }

  BoxWithConstraints(modifier = modifier) {
    val density = LocalDensity.current
    val container = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
    val fitted = fittedSize(intrinsicSize, container)

    Box(
      modifier = Modifier
        .fillMaxSize()
        .pointerInput(model) {
          detectTransformGestures { _, pan, zoom, _ ->
            val next = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
            // Posun se škáluje spolu s obrázkem (graphicsLayer translation je v nezvětšených
            // pixelech), proto se pan bere tak, jak přišel, a jen se ořízne na meze.
            offset = clampPan(offset + pan, next, fitted, container)
            scale = next
          }
        }
        .pointerInput(model) {
          detectTapGestures(
            onDoubleTap = {
              if (scale > MIN_SCALE) {
                scale = MIN_SCALE
                offset = Offset.Zero
              } else {
                scale = DOUBLE_TAP_SCALE
                offset = clampPan(offset, DOUBLE_TAP_SCALE, fitted, container)
              }
            },
          )
        },
    ) {
      AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        onSuccess = { intrinsicSize = it.painter.intrinsicSize },
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
          },
      )
    }
  }
}

/**
 * Rozměr, na který `ContentScale.Fit` obrázek doopravdy vykreslí — vepsaný do kontejneru se
 * zachovaným poměrem stran. Dokud se obrázek nenačte, je to celý kontejner.
 */
private fun fittedSize(intrinsic: Size, container: Size): Size {
  if (intrinsic == Size.Unspecified || intrinsic.width <= 0f || intrinsic.height <= 0f) return container
  if (container.width <= 0f || container.height <= 0f) return container
  val factor = min(container.width / intrinsic.width, container.height / intrinsic.height)
  return Size(intrinsic.width * factor, intrinsic.height * factor)
}

/**
 * Ořez posunu, aby zvětšená fotka nešla vytáhnout mimo obraz: v každé ose se smí posunout
 * nanejvýš o polovinu toho, oč zvětšený obrázek přesahuje kontejner. Když nepřesahuje (fotka je
 * v ose menší než kontejner), je mez nula a fotka zůstane vystředěná.
 */
private fun clampPan(offset: Offset, scale: Float, fitted: Size, container: Size): Offset {
  val maxX = max(0f, (fitted.width * scale - container.width) / 2f)
  val maxY = max(0f, (fitted.height * scale - container.height) / 2f)
  return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}
