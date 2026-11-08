package cz.kvalitacena.ui.receipt.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import cz.kvalitacena.R
import cz.kvalitacena.ui.receipt.capture.MaskCorner
import cz.kvalitacena.ui.receipt.capture.MaskHit
import cz.kvalitacena.ui.receipt.capture.MaskRect
import cz.kvalitacena.ui.receipt.capture.hitTest
import cz.kvalitacena.ui.receipt.capture.maskFromDrag
import cz.kvalitacena.ui.receipt.capture.movedBy
import cz.kvalitacena.ui.receipt.capture.resizedTo
import cz.kvalitacena.ui.theme.Spacing
import java.io.File
import kotlin.math.min

/**
 * Barva návrhu zakrytí — vědomá výjimka z `MaterialTheme.colorScheme` (docs/design.md,
 * „Barevné tokeny"): podklad je fotka papírové účtenky, ne povrch appky, takže barva podle
 * tématu (v tmavém režimu bledá `error`) by na bílém papíře zanikla. Stejná jako webová
 * `--kc-mask-stroke`.
 */
private val MaskStroke = Color(0xFFFF4D4F)
private const val HATCH_ALPHA = 0.45f
private const val TINT_ALPHA = 0.12f

/**
 * Díl účtenky s maskami přes obrázek — v seznamu kontroly i v editoru. Maska je NÁVRH, ne
 * výsledek: průsvitná šrafa s obrysem, pod kterou je obsah vidět. Výchozí pás appka navrhuje
 * slepě podle pozice (`defaultMaskProposal`), takže uživatel musí poznat, když zakrývá položky
 * místo karty. Černě se maska vypálí až při odeslání (`ReceiptImageProcessor.burnMasks`).
 * Vypnuté zakrytí ([enabled] = false) je jen čárkovaný obrys — při odeslání se nezačerní.
 */
@Composable
fun MaskedPartImage(
  file: File,
  width: Int,
  height: Int,
  masks: List<MaskRect>,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  selectedIndex: Int? = null,
  showHandles: Boolean = false,
) {
  val density = LocalDensity.current
  val stroke = with(density) { 2.dp.toPx() }
  val hatchGap = with(density) { Spacing.sm.toPx() }
  val handleRadius = with(density) { Spacing.sm.toPx() }
  val selectedColor = MaterialTheme.colorScheme.primary
  Box(modifier) {
    AsyncImage(
      model = file,
      contentDescription = null,
      contentScale = ContentScale.FillBounds,
      modifier = Modifier.fillMaxSize(),
    )
    Canvas(Modifier.fillMaxSize()) {
      masks.forEachIndexed { index, mask ->
        val topLeft = Offset(mask.left * size.width, mask.top * size.height)
        val rectSize = Size((mask.right - mask.left) * size.width, (mask.bottom - mask.top) * size.height)
        val color = if (index == selectedIndex) selectedColor else MaskStroke
        if (enabled) {
          drawRect(MaskStroke.copy(alpha = TINT_ALPHA), topLeft, rectSize)
          drawHatch(topLeft, rectSize, MaskStroke.copy(alpha = HATCH_ALPHA), hatchGap, stroke / 2)
          drawRect(color, topLeft, rectSize, style = Stroke(width = stroke))
        } else {
          drawRect(
            color,
            topLeft,
            rectSize,
            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(hatchGap, hatchGap / 2))),
          )
        }
        if (showHandles) {
          listOf(
            topLeft,
            Offset(topLeft.x + rectSize.width, topLeft.y),
            Offset(topLeft.x, topLeft.y + rectSize.height),
            Offset(topLeft.x + rectSize.width, topLeft.y + rectSize.height),
          ).forEach { drawCircle(color, handleRadius, it) }
        }
      }
    }
  }
}

/** Diagonální šrafa uvnitř obdélníku — čáry pod 45° po [gap] pixelech, oříznuté na obdélník. */
private fun DrawScope.drawHatch(topLeft: Offset, rectSize: Size, color: Color, gap: Float, width: Float) {
  clipRect(topLeft.x, topLeft.y, topLeft.x + rectSize.width, topLeft.y + rectSize.height) {
    var offset = -rectSize.height
    while (offset < rectSize.width) {
      drawLine(
        color,
        start = Offset(topLeft.x + offset, topLeft.y + rectSize.height),
        end = Offset(topLeft.x + offset + rectSize.height, topLeft.y),
        strokeWidth = width,
      )
      offset += gap
    }
  }
}

/** Co právě dělá prst v editoru — rozhoduje se jednou, na začátku tažení ([hitTest]). */
private sealed interface MaskDrag {
  data class Draw(val start: Offset, val end: Offset) : MaskDrag

  data class Move(val index: Int, val original: MaskRect, val start: Offset) : MaskDrag

  data class Resize(val index: Int, val corner: MaskCorner) : MaskDrag
}

/**
 * Editor zakrytí přes celou obrazovku (podle webového `receipt-mask-editor`): tažení za roh
 * mění velikost, tažení uvnitř masku posouvá, tažení po volné ploše kreslí novou. Ťuknutí
 * masku vybere — smaže se až tlačítkem, protože když se maskou dá hýbat, mazání ťuknutím by
 * ji smazalo omylem při každém nepovedeném posunu. Obrázek se vejde celý (bez posouvání), aby
 * tažení nesoupeřilo se scrollem.
 */
@Composable
fun MaskEditorDialog(
  file: File,
  imageWidth: Int,
  imageHeight: Int,
  initialMasks: List<MaskRect>,
  onSave: (List<MaskRect>) -> Unit,
  onDismiss: () -> Unit,
) {
  var masks by remember(file) { mutableStateOf(initialMasks) }
  var selected by remember(file) { mutableStateOf<Int?>(null) }
  var drag by remember { mutableStateOf<MaskDrag?>(null) }
  val highlight = MaterialTheme.colorScheme.primary
  val density = LocalDensity.current
  val stroke = with(density) { 2.dp.toPx() }
  // Dosah úchytu = polovina dotykového cíle 48 dp (docs/design.md, „Dotykové cíle").
  val handleReach = with(density) { 24.dp.toPx() }

  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      Column(Modifier.fillMaxSize().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(R.string.receipt_mask_editor_title), style = MaterialTheme.typography.titleMedium)
        Text(
          stringResource(R.string.receipt_mask_editor_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
          val boxWidth = with(density) { maxWidth.toPx() }
          val boxHeight = with(density) { maxHeight.toPx() }
          val scale = min(boxWidth / imageWidth, boxHeight / imageHeight)
          val shownWidth = with(density) { (imageWidth * scale).toDp() }
          val shownHeight = with(density) { (imageHeight * scale).toDp() }
          Box(
            Modifier
              .size(shownWidth, shownHeight)
              .background(MaterialTheme.colorScheme.surfaceVariant)
              .pointerInput(file) {
                detectTapGestures { tap ->
                  val hit = hitTest(
                    masks, tap.x / size.width, tap.y / size.height,
                    handleReach / size.width, handleReach / size.height,
                  )
                  selected = when (hit) {
                    is MaskHit.Corner -> hit.index
                    is MaskHit.Inside -> hit.index
                    MaskHit.None -> null
                  }
                }
              }
              .pointerInput(file) {
                detectDragGestures(
                  onDragStart = { start ->
                    val hit = hitTest(
                      masks, start.x / size.width, start.y / size.height,
                      handleReach / size.width, handleReach / size.height,
                    )
                    drag = when (hit) {
                      is MaskHit.Corner -> MaskDrag.Resize(hit.index, hit.corner)
                      is MaskHit.Inside -> MaskDrag.Move(hit.index, masks[hit.index], start)
                      MaskHit.None -> MaskDrag.Draw(start, start)
                    }
                    selected = when (hit) {
                      is MaskHit.Corner -> hit.index
                      is MaskHit.Inside -> hit.index
                      MaskHit.None -> null
                    }
                  },
                  onDrag = { change, _ ->
                    val position = change.position
                    when (val current = drag) {
                      is MaskDrag.Draw -> drag = current.copy(end = position)
                      is MaskDrag.Move -> {
                        // Od původní polohy, ne po krocích — posun pak „neujíždí" zaokrouhlením.
                        val moved = current.original.movedBy(
                          (position.x - current.start.x) / size.width,
                          (position.y - current.start.y) / size.height,
                        )
                        masks = masks.mapIndexed { i, m -> if (i == current.index) moved else m }
                      }
                      is MaskDrag.Resize -> {
                        val x = position.x / size.width
                        val y = position.y / size.height
                        masks = masks.mapIndexed { i, m -> if (i == current.index) m.resizedTo(current.corner, x, y) else m }
                      }
                      null -> Unit
                    }
                    change.consume()
                  },
                  onDragEnd = {
                    val current = drag
                    if (current is MaskDrag.Draw) {
                      maskFromDrag(
                        current.start.x / size.width,
                        current.start.y / size.height,
                        current.end.x / size.width,
                        current.end.y / size.height,
                      )?.let {
                        masks = masks + it
                        selected = masks.lastIndex
                      }
                    }
                    drag = null
                  },
                  onDragCancel = { drag = null },
                )
              },
          ) {
            MaskedPartImage(
              file, imageWidth, imageHeight, masks, Modifier.fillMaxSize(),
              selectedIndex = selected, showHandles = true,
            )
            Canvas(Modifier.fillMaxSize()) {
              val current = drag
              if (current is MaskDrag.Draw) {
                val topLeft = Offset(min(current.start.x, current.end.x), min(current.start.y, current.end.y))
                val rectSize = Size(
                  kotlin.math.abs(current.end.x - current.start.x),
                  kotlin.math.abs(current.end.y - current.start.y),
                )
                drawRect(highlight, topLeft = topLeft, size = rectSize, style = Stroke(width = stroke))
              }
            }
          }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.Center) {
          TextButton(
            onClick = {
              val index = selected ?: return@TextButton
              masks = masks.filterIndexed { i, _ -> i != index }
              selected = null
            },
            enabled = selected != null,
          ) {
            Text(stringResource(R.string.receipt_mask_remove_selected))
          }
          TextButton(onClick = { masks = emptyList(); selected = null }, enabled = masks.isNotEmpty()) {
            Text(stringResource(R.string.receipt_mask_clear))
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
          OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.common_cancel))
          }
          Button(onClick = { onSave(masks) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.receipt_mask_save))
          }
        }
      }
    }
  }
}
