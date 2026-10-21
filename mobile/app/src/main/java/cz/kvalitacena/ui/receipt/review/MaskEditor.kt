package cz.kvalitacena.ui.receipt.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import cz.kvalitacena.R
import cz.kvalitacena.ui.receipt.capture.MaskRect
import cz.kvalitacena.ui.receipt.capture.maskFromDrag
import cz.kvalitacena.ui.theme.Spacing
import java.io.File
import kotlin.math.min

/**
 * Díl účtenky s maskami přes obrázek — v seznamu kontroly i v editoru. Maska je plná, ne
 * průhledná: uživatel má vidět výsledek, ne návrh. Barva `scrim` je v Material schématu černá
 * v obou režimech — přesně tak skončí maska vypálená v pixelech (`ReceiptImageProcessor.burnMasks`).
 */
@Composable
fun MaskedPartImage(file: File, width: Int, height: Int, masks: List<MaskRect>, modifier: Modifier = Modifier) {
  val maskColor = MaterialTheme.colorScheme.scrim
  Box(modifier) {
    AsyncImage(
      model = file,
      contentDescription = null,
      contentScale = ContentScale.FillBounds,
      modifier = Modifier.fillMaxSize(),
    )
    Canvas(Modifier.fillMaxSize()) {
      masks.forEach { mask ->
        drawRect(
          maskColor,
          topLeft = Offset(mask.left * size.width, mask.top * size.height),
          size = Size((mask.right - mask.left) * size.width, (mask.bottom - mask.top) * size.height),
        )
      }
    }
  }
}

/**
 * Editor zakrytí přes celou obrazovku — tažením prstu se kreslí obdélník, ťuknutím na existující
 * se smaže. Obrázek se vejde celý (bez posouvání), aby tažení nesoupeřilo se scrollem.
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
  var dragStart by remember { mutableStateOf<Offset?>(null) }
  var dragEnd by remember { mutableStateOf<Offset?>(null) }
  val highlight = MaterialTheme.colorScheme.primary
  val stroke = with(LocalDensity.current) { 2.dp.toPx() }

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
          val density = LocalDensity.current
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
                  val x = tap.x / size.width
                  val y = tap.y / size.height
                  val hit = masks.lastOrNull { it.contains(x, y) }
                  if (hit != null) masks = masks - hit
                }
              }
              .pointerInput(file) {
                detectDragGestures(
                  onDragStart = { dragStart = it; dragEnd = it },
                  onDrag = { change, _ -> dragEnd = change.position },
                  onDragEnd = {
                    val start = dragStart
                    val end = dragEnd
                    if (start != null && end != null) {
                      maskFromDrag(
                        start.x / size.width,
                        start.y / size.height,
                        end.x / size.width,
                        end.y / size.height,
                      )?.let { masks = masks + it }
                    }
                    dragStart = null
                    dragEnd = null
                  },
                  onDragCancel = { dragStart = null; dragEnd = null },
                )
              },
          ) {
            MaskedPartImage(file, imageWidth, imageHeight, masks, Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
              val start = dragStart
              val end = dragEnd
              if (start != null && end != null) {
                val topLeft = Offset(min(start.x, end.x), min(start.y, end.y))
                val rectSize = Size(kotlin.math.abs(end.x - start.x), kotlin.math.abs(end.y - start.y))
                drawRect(highlight, topLeft = topLeft, size = rectSize, style = Stroke(width = stroke))
              }
            }
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
          TextButton(onClick = { masks = masks.dropLast(1) }, enabled = masks.isNotEmpty()) {
            Text(stringResource(R.string.receipt_mask_undo))
          }
          TextButton(onClick = { masks = emptyList() }, enabled = masks.isNotEmpty()) {
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
