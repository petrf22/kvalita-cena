package cz.kvalitacena.ui.receipt.list

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.Receipt
import cz.kvalitacena.receipt.PendingReceiptStore
import cz.kvalitacena.receipt.ReceiptPrefs
import cz.kvalitacena.receipt.ReceiptWork
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.common.toUiText
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 20

/**
 * Seznam účtenek: čekající v telefonu (fronta, stav z WorkManageru) nad účtenkami ze serveru.
 * Když některá čekající doběhne, seznam se sám obnoví — nová účtenka se objeví jako „čeká na
 * zpracování" místo „odesílá se".
 */
class ReceiptListViewModel(
  private val appContext: Context,
  private val graphQlClient: GraphQlClient,
  private val pendingStore: PendingReceiptStore,
  private val prefs: ReceiptPrefs,
) : ViewModel() {

  var items by mutableStateOf<List<Receipt>>(emptyList())
    private set
  var totalCount by mutableIntStateOf(0)
    private set
  var hasMore by mutableStateOf(false)
    private set
  var loading by mutableStateOf(true)
    private set
  var loadingMore by mutableStateOf(false)
    private set
  var error by mutableStateOf<UiText?>(null)
    private set
  var loadedOnce by mutableStateOf(false)
    private set
  var actionError by mutableStateOf<UiText?>(null)
    private set

  val pending = pendingStore.pending

  /** Stav odeslání čekajících účtenek podle id v [PendingReceiptStore]. */
  val uploadStates: StateFlow<Map<String, WorkInfo.State>> =
    WorkManager.getInstance(appContext).getWorkInfosByTagFlow(ReceiptWork.TAG_UPLOAD)
      .map { infos ->
        infos.mapNotNull { info -> ReceiptWork.pendingIdFromTags(info.tags)?.let { it to info } }
          .groupBy({ it.first }, { it.second })
          // Po „Odeslat znovu" vzniká pod stejným jménem nová práce — rozhoduje ta nedokončená.
          .mapValues { (_, list) -> (list.firstOrNull { !it.state.isFinished } ?: list.last()).state }
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

  private val handledSuccess = mutableSetOf<String>()

  init {
    refresh()
    viewModelScope.launch {
      uploadStates.collect { states ->
        val finished = states.filterValues { it == WorkInfo.State.SUCCEEDED }.keys - handledSuccess
        if (finished.isNotEmpty()) {
          handledSuccess += finished
          pendingStore.refresh()
          refresh(silent = true)
        }
        if (states.values.any { it == WorkInfo.State.FAILED }) pendingStore.refresh()
      }
    }
  }

  /** Návrat na obrazovku (z detailu, po potvrzení cen) — tiché obnovení bez spinneru. */
  fun onShown() {
    pendingStore.refresh()
    if (loadedOnce) refresh(silent = true)
  }

  fun refresh(silent: Boolean = false) {
    if (!silent) loading = true
    error = null
    viewModelScope.launch {
      try {
        val page = graphQlClient.myReceipts(first = PAGE_SIZE, offset = 0)
        items = page.items
        totalCount = page.totalCount
        hasMore = page.hasMore
        loadedOnce = true
        // Verze souhlasu pro čekající účtenky, které server odmítl kvůli starší verzi.
        runCatching { graphQlClient.receiptSummary() }.onSuccess { prefs.serverConsentVersion = it.consentVersion }
      } catch (e: Exception) {
        if (!silent || !loadedOnce) error = e.toUiText()
      } finally {
        loading = false
      }
    }
  }

  fun loadMore() {
    if (loadingMore || !hasMore) return
    loadingMore = true
    viewModelScope.launch {
      try {
        val page = graphQlClient.myReceipts(first = PAGE_SIZE, offset = items.size)
        items = items + page.items.filter { new -> items.none { it.id == new.id } }
        totalCount = page.totalCount
        hasMore = page.hasMore
      } catch (e: Exception) {
        actionError = e.toUiText()
      } finally {
        loadingMore = false
      }
    }
  }

  fun delete(id: String) {
    actionError = null
    viewModelScope.launch {
      try {
        graphQlClient.deleteReceipt(id)
        items = items.filterNot { it.id == id }
        totalCount = (totalCount - 1).coerceAtLeast(0)
      } catch (e: Exception) {
        actionError = e.toUiText()
      }
    }
  }

  fun retryPending(id: String) {
    pendingStore.clearError(id)
    ReceiptWork.enqueueUpload(appContext, id)
  }

  /** Server mezitím zvedl verzi souhlasu — nový souhlas platí i pro tuhle čekající účtenku. */
  fun acceptConsentAndRetry(id: String, archive: Boolean) {
    val version = prefs.serverConsentVersion
    prefs.acceptConsent(version, archive)
    pendingStore.clearError(id, consentVersion = version, archiveConsent = archive)
    ReceiptWork.enqueueUpload(appContext, id)
  }

  fun deletePending(id: String) {
    ReceiptWork.cancelUpload(appContext, id)
    pendingStore.delete(id)
  }

  fun dismissActionError() {
    actionError = null
  }
}
