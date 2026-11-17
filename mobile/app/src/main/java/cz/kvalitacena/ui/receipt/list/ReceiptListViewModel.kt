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
import cz.kvalitacena.R
import cz.kvalitacena.auth.AuthRepository
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.Receipt
import cz.kvalitacena.network.ReceiptFilterCounts
import cz.kvalitacena.receipt.PendingReceipt
import cz.kvalitacena.receipt.PendingReceiptStore
import cz.kvalitacena.receipt.RECEIPT_CONSENT_VERSION
import cz.kvalitacena.receipt.ReceiptPrefs
import cz.kvalitacena.receipt.ReceiptWork
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.common.toUiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 20

/**
 * Strop `first` v `ReceiptService.myReceipts` — víc server v jedné stránce nevrátí. Obnovení
 * načítá tolik, kolik už je v seznamu dočteno, ať smazání ani návrat z detailu nezkrátí seznam
 * zpátky na první stránku.
 */
private const val MAX_RELOAD_SIZE = 100

/**
 * Seznam účtenek: čekající v telefonu (fronta, stav z WorkManageru) nad účtenkami ze serveru.
 * Když některá čekající doběhne, seznam se sám obnoví — nová účtenka se objeví jako „čeká na
 * zpracování" místo „odesílá se".
 *
 * Účtenky ze serveru se filtrují podle stavu ([filter]); čekající v telefonu filtr nemají — jsou
 * to stav telefonu, ne serveru, a můžou čekat na akci (souhlas, nové přihlášení).
 */
class ReceiptListViewModel(
  private val appContext: Context,
  private val graphQlClient: GraphQlClient,
  private val pendingStore: PendingReceiptStore,
  private val prefs: ReceiptPrefs,
  private val authRepository: AuthRepository,
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
  var actionError by mutableStateOf<UiText?>(null)
    private set

  /** Zvolená skupina; dokud výchozí volba není rozhodnutá, je to filtr prvního dotazu. */
  var filter by mutableStateOf(FIRST_RECEIPT_FILTER)
    private set

  /** Počty všech skupin z poslední odpovědi; `null`, dokud se seznam ani jednou nenačetl. */
  var filterCounts by mutableStateOf<ReceiptFilterCounts?>(null)
    private set

  val loadedOnce: Boolean get() = filterCounts != null

  /**
   * Výchozí volbu ([initialReceiptFilter]) rozhoduje jen první odpověď — návrat z detailu ani
   * tiché obnovení ji nemění, jinak by uživateli „K vyřízení" po vyřízení poslední účtenky
   * samo přeskočilo na „Vše" a nevěděl by proč.
   */
  private var filterDecided = false

  /**
   * Pořadí načtení: odpověď, která mezitím zastarala (rychlé přepnutí filtru, tiché obnovení
   * přes rozjeté „Načíst další"), se zahodí, ať do seznamu jednoho filtru nedorazí položky
   * jiného. Rušení jobu samo nestačí — zrušený job dobíhá `finally` až po startu nového.
   */
  private var generation = 0
  private var loadJob: Job? = null
  private var loadMoreJob: Job? = null

  private val currentUserUid = MutableStateFlow<String?>(null)

  /**
   * Čekající účtenky jen přihlášeného účtu — účtenky jiného účtu v tomhle telefonu (odhlášení
   * před odesláním) se neukazují ani neodesílají, dokud se jejich vlastník nepřihlásí zpátky.
   * Účtenka bez vlastníka vznikla před touhle kontrolou a ukazuje se všem.
   */
  val pending: StateFlow<List<PendingReceipt>> = combine(pendingStore.pending, currentUserUid) { list, uid ->
    list.filter { it.ownerUid == null || it.ownerUid == uid }
  }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
    viewModelScope.launch(Dispatchers.IO) { currentUserUid.value = authRepository.currentUserUid() }
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
    // Tiché obnovení, které přebije rozjeté viditelné načítání (změna filtru, „Zkusit znovu"),
    // převezme i jeho spinner a hlášení chyby — jinak by zrušené načítání skončilo bez obojího.
    val visible = !silent || loading
    if (visible) loading = true
    error = null
    val requestGeneration = ++generation
    loadJob?.cancel()
    loadMoreJob?.cancel()
    loadingMore = false
    val size = items.size.coerceIn(PAGE_SIZE, MAX_RELOAD_SIZE)
    loadJob = viewModelScope.launch {
      try {
        var requested = filter
        var page = graphQlClient.myReceipts(first = size, offset = 0, filter = requested.name)
        if (!filterDecided) {
          val initial = initialReceiptFilter(page.filterCounts)
          if (initial != requested) {
            requested = initial
            page = graphQlClient.myReceipts(first = size, offset = 0, filter = requested.name)
          }
        }
        if (requestGeneration != generation) return@launch
        filter = requested
        filterDecided = true
        items = page.items
        totalCount = page.totalCount
        hasMore = page.hasMore
        filterCounts = page.filterCounts
        // Verze souhlasu pro čekající účtenky, které server odmítl kvůli starší verzi.
        runCatching { graphQlClient.receiptSummary() }.onSuccess { prefs.serverConsentVersion = it.consentVersion }
      } catch (e: Exception) {
        if (requestGeneration == generation && (visible || !loadedOnce)) error = e.toUiText()
      } finally {
        if (requestGeneration == generation) loading = false
      }
    }
  }

  /** Volba filtru uživatelem — seznam se načte od začátku, stará skupina nesmí probleskout. */
  fun selectFilter(value: ReceiptListFilter) {
    filterDecided = true
    if (value == filter) return
    filter = value
    items = emptyList()
    hasMore = false
    totalCount = filterCounts?.countFor(value) ?: 0
    actionError = null
    refresh()
  }

  fun loadMore() {
    if (loadingMore || loading || !hasMore) return
    loadingMore = true
    val requestGeneration = generation
    val requested = filter
    val offset = items.size
    loadMoreJob = viewModelScope.launch {
      try {
        val page = graphQlClient.myReceipts(first = PAGE_SIZE, offset = offset, filter = requested.name)
        if (requestGeneration != generation) return@launch
        items = items + page.items.filter { new -> items.none { it.id == new.id } }
        totalCount = page.totalCount
        hasMore = page.hasMore
        filterCounts = page.filterCounts
      } catch (e: Exception) {
        if (requestGeneration == generation) actionError = e.toUiText()
      } finally {
        if (requestGeneration == generation) loadingMore = false
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
        // Počty na štítcích filtru (a doplnění stránky) zná jen server — do které skupiny
        // smazaná účtenka patřila, klient sám nezařazuje.
        refresh(silent = true)
      } catch (e: Exception) {
        actionError = e.toUiText()
      }
    }
  }

  fun retryPending(id: String) {
    pendingStore.clearError(id)
    ReceiptWork.enqueueUpload(appContext, id)
  }

  /**
   * Smí se ukázat dialog souhlasu? Když server chce znění, které tahle verze appky nezná,
   * ukáže místo toho výzvu k aktualizaci (docs/soukromi.md, „Účtenky").
   */
  fun canAskConsent(): Boolean {
    if (!prefs.consentOutdated) return true
    actionError = UiText.Res(R.string.receipt_consent_outdated)
    return false
  }

  /** Server mezitím zvedl verzi souhlasu — nový souhlas platí i pro tuhle čekající účtenku. */
  fun acceptConsentAndRetry(id: String, archive: Boolean) {
    prefs.acceptConsent(RECEIPT_CONSENT_VERSION, archive)
    pendingStore.clearError(id, consentVersion = RECEIPT_CONSENT_VERSION, archiveConsent = archive)
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
