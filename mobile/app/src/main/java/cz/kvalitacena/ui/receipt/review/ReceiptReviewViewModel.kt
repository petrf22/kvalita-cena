package cz.kvalitacena.ui.receipt.review

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.R
import cz.kvalitacena.auth.AuthRepository
import cz.kvalitacena.crash.AppLog
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.receipt.PendingReceiptStore
import cz.kvalitacena.receipt.RECEIPT_CONSENT_VERSION
import cz.kvalitacena.receipt.ReceiptDraftStore
import cz.kvalitacena.receipt.ReceiptNotifications
import cz.kvalitacena.receipt.ReceiptPrefs
import cz.kvalitacena.receipt.ReceiptWork
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.receipt.capture.MaskRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kontrola účtenky před odesláním — pořadí dílů, zakrytí citlivých údajů a souhlas
 * (docs/soukromi.md, „Účtenky"). Odesláním se koncept přesune do fronty a WorkManager ho pošle,
 * až bude signál; obrazovka tedy funguje stejně online i offline.
 */
class ReceiptReviewViewModel(
  private val appContext: Context,
  private val draftStore: ReceiptDraftStore,
  private val pendingStore: PendingReceiptStore,
  private val prefs: ReceiptPrefs,
  private val graphQlClient: GraphQlClient,
  private val authRepository: AuthRepository,
) : ViewModel() {

  val draft = draftStore.draft

  var consentVisible by mutableStateOf(false)
    private set

  var submitting by mutableStateOf(false)
    private set

  var submitError by mutableStateOf<UiText?>(null)
    private set

  /** Účtenka je ve frontě — obrazovka se může zeptat na notifikace a odejít. */
  var submitted by mutableStateOf(false)
    private set

  /** Díl otevřený v editoru zakrytí. */
  var editingIndex by mutableStateOf<Int?>(null)

  init {
    viewModelScope.launch {
      // Offline je v pořádku: souhlas se dá i bez signálu (poslední známá verze) a kdyby
      // server mezitím verzi zvedl, upload vrátí RECEIPT_CONSENT_REQUIRED a seznam účtenek
      // se zeptá znovu.
      runCatching { graphQlClient.receiptSummary() }.onSuccess {
        prefs.serverConsentVersion = it.consentVersion
      }
    }
  }

  /**
   * Návrat na kontrolu (nový díl, „nafotit znovu") — výchozí zakrytí patří vždy poslednímu
   * dílu, i když se poslední díl mezitím změnil (ReceiptDraftStore.proposeDefaultMask).
   */
  fun onShown() {
    draftStore.proposeDefaultMask()
  }

  fun moveUp(index: Int) = draftStore.movePart(index, index - 1)

  fun moveDown(index: Int) = draftStore.movePart(index, index + 1)

  fun remove(index: Int) = draftStore.removePart(index)

  fun saveMasks(index: Int, masks: List<MaskRect>) {
    draftStore.setMasks(index, masks)
    editingIndex = null
  }

  fun requestSubmit() {
    if (prefs.consentOutdated) {
      // Text v téhle verzi appky už neplatí — souhlas s ním by byl souhlas s něčím jiným.
      submitError = UiText.Res(R.string.receipt_consent_outdated)
      return
    }
    if (prefs.needsConsent()) {
      consentVisible = true
    } else {
      submit(prefs.acceptedConsentVersion, prefs.archiveConsent)
    }
  }

  fun dismissConsent() {
    consentVisible = false
  }

  fun acceptConsent(archive: Boolean) {
    prefs.acceptConsent(RECEIPT_CONSENT_VERSION, archive)
    consentVisible = false
    submit(RECEIPT_CONSENT_VERSION, archive)
  }

  private fun submit(version: Int, archive: Boolean) {
    if (submitting) return
    submitting = true
    submitError = null
    viewModelScope.launch {
      try {
        val owner = withContext(Dispatchers.IO) { authRepository.currentUserUid() }
        val pending = pendingStore.commitDraft(draftStore, version, archive, owner)
        ReceiptNotifications.ensureChannel(appContext)
        ReceiptWork.enqueueUpload(appContext, pending.id)
        submitted = true
      } catch (e: Exception) {
        AppLog.e("Receipt could not be queued for upload", e)
        submitError = UiText.Res(R.string.receipt_review_submit_failed)
      } finally {
        submitting = false
      }
    }
  }
}
