package cz.kvalitacena.ui.receipt.capture

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.R
import cz.kvalitacena.crash.AppLog
import cz.kvalitacena.receipt.ReceiptDraftStore
import cz.kvalitacena.ui.common.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Snímání účtenky kamerou. Díly jdou rovnou do konceptu ([ReceiptDraftStore]) — pokračuje se
 * v něm i po návratu z kontroly („přidat další díl") a přežije i zabití procesu.
 *
 * S [retakeIndex] snímá jen jeden díl: první uložený (automaticky nebo ruční spouští) nahradí
 * díl na té pozici a obrazovka se vrátí do kontroly ([retakeDone]).
 */
class ReceiptCaptureViewModel(
  private val draftStore: ReceiptDraftStore,
  private val retakeIndex: Int?,
) : ViewModel() {

  val isRetake: Boolean get() = retakeIndex != null

  /** Nativní OpenCV — bez něj snímání neumí nic, ani ruční spoušť (narovnání a JPEG). */
  val openCvAvailable: Boolean = ReceiptImageProcessor.ensureLoaded()

  private val _frame = MutableStateFlow<FrameResult?>(null)
  val frame: StateFlow<FrameResult?> = _frame

  val draft = draftStore.draft

  var processing by mutableStateOf(false)
    private set

  var error by mutableStateOf<UiText?>(null)
    private set

  var retakeDone by mutableStateOf(false)
    private set

  /** Kamera se nepodařila spustit (obsazená, zakázaná politikou zařízení…). */
  var cameraError by mutableStateOf(false)

  val analyzer = ReceiptFrameAnalyzer(
    onFrame = { _frame.value = it },
    onPart = ::onAutoPart,
    initialParts = if (retakeIndex != null) 0 else draftStore.draft.value.parts.size,
    maxParts = if (retakeIndex != null) 1 else CaptureThresholds.MAX_PARTS,
  )

  /**
   * Díl z analyzátoru — volá se na jeho vlákně (soubor je malý a zápis rychlý), stav obrazovky
   * se mění až na hlavním vlákně.
   */
  private fun onAutoPart(part: EncodedPart) {
    if (retakeIndex != null) {
      analyzer.autoCapture = false
      draftStore.replacePart(retakeIndex, part)
      viewModelScope.launch { retakeDone = true }
    } else {
      draftStore.addPart(part)
    }
  }

  /** Ruční spoušť — snímek z `ImageCapture`, narovná se, když se účtenka najde; jinak celý. */
  fun onManualPhoto(jpeg: ByteArray, rotationDegrees: Int) {
    processStill { ReceiptImageProcessor.processStill(jpeg, rotationDegrees, splitTall = false) }
  }

  /** Import z galerie (jen debug build) — dlouhý sken se rozřeže na díly s překryvem. */
  fun importImages(context: Context, uris: List<Uri>) {
    if (uris.isEmpty()) return
    processStill {
      uris.flatMap { uri ->
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
          ?: error("Cannot read image $uri")
        ReceiptImageProcessor.processStill(bytes, rotationDegrees = null, splitTall = true)
      }
    }
  }

  private fun processStill(produce: () -> List<EncodedPart>) {
    if (processing) return
    processing = true
    error = null
    viewModelScope.launch {
      try {
        val parts = withContext(Dispatchers.Default) { produce() }
        withContext(Dispatchers.IO) { store(parts) }
      } catch (e: Exception) {
        AppLog.e("Receipt photo could not be processed", e)
        error = UiText.Res(R.string.receipt_capture_process_failed)
      } finally {
        processing = false
      }
    }
  }

  private fun store(parts: List<EncodedPart>) {
    if (parts.isEmpty()) return
    if (retakeIndex != null) {
      draftStore.replacePart(retakeIndex, parts.first())
      analyzer.autoCapture = false
      retakeDone = true
      return
    }
    val room = CaptureThresholds.MAX_PARTS - draftStore.draft.value.parts.size
    parts.take(room.coerceAtLeast(0)).forEach { draftStore.addPart(it) }
    if (parts.size > room) error = UiText.Res(R.string.receipt_capture_limit, listOf(CaptureThresholds.MAX_PARTS))
    // Posun pro další automatický díl se počítá od právě uloženého.
    analyzer.resetParts(draftStore.draft.value.parts.size)
  }

  /** Smazat poslední díl přímo při snímání (nepovedený záběr) — v kontrole jde smazat kterýkoli. */
  fun undoLastPart() {
    val count = draftStore.draft.value.parts.size
    if (count == 0) return
    draftStore.removePart(count - 1)
    analyzer.resetParts(count - 1)
  }

  fun dismissError() {
    error = null
  }

  override fun onCleared() {
    analyzer.release()
  }
}
