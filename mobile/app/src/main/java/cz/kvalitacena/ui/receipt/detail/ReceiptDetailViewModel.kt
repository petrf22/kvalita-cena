package cz.kvalitacena.ui.receipt.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.network.ConfirmReceiptLinesResult
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.MediaClient
import cz.kvalitacena.network.ProductSummary
import cz.kvalitacena.network.Receipt
import cz.kvalitacena.network.ReceiptLine
import cz.kvalitacena.network.Store
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.common.toUiText
import java.time.OffsetDateTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SEARCH_DEBOUNCE_MS = 300L

/**
 * Detail účtenky a párování řádků se zbožím. Výsledek workeru je jen návrh (docs/ai.md, „AI
 * nikdy nerozhoduje"): řádky, které server spároval sám (SUGGESTED), jsou předem zaškrtnuté,
 * ale cenu zapíše až „Potvrdit spárované".
 */
class ReceiptDetailViewModel(
  private val receiptId: String,
  private val graphQlClient: GraphQlClient,
  private val mediaClient: MediaClient,
) : ViewModel() {

  var receipt by mutableStateOf<Receipt?>(null)
    private set
  var loading by mutableStateOf(true)
    private set
  var error by mutableStateOf<UiText?>(null)
    private set
  var notFound by mutableStateOf(false)
    private set
  var actionError by mutableStateOf<UiText?>(null)
    private set
  var busyLineIds by mutableStateOf<Set<String>>(emptySet())
    private set
  var headerSaving by mutableStateOf(false)
    private set

  /** Řádky zaškrtnuté k potvrzení — výchozí jsou všechny SUGGESTED. */
  var selected by mutableStateOf<Set<String>>(emptySet())
    private set

  var confirming by mutableStateOf(false)
    private set
  var confirmResult by mutableStateOf<ConfirmReceiptLinesResult?>(null)
    private set
  var deleted by mutableStateOf(false)
    private set

  // --- výběr obchodu (StorePicker) ---
  var storeEditing by mutableStateOf(false)
  var storeQuery by mutableStateOf("")
    private set
  var storeSuggestions by mutableStateOf<List<Store>>(emptyList())
    private set
  var storeSearching by mutableStateOf(false)
    private set
  private var storeSearchJob: Job? = null

  // --- našeptávač zboží k řádku ---
  var suggestionLine by mutableStateOf<ReceiptLine?>(null)
    private set
  var suggestionQuery by mutableStateOf("")
    private set
  var suggestionItems by mutableStateOf<List<ProductSummary>>(emptyList())
    private set
  var suggestionsLoading by mutableStateOf(false)
    private set
  var suggestionsError by mutableStateOf<UiText?>(null)
    private set
  private var suggestionJob: Job? = null

  /** Řádek, pro který se právě zakládá nové zboží (formulář zboží je jiná obrazovka). */
  var newProductLineId: String? = null
    private set

  /** Obrázky účtenky v paměti — jen pro vlastníka, nikdy do diskové cache (MediaClient.receiptImage). */
  var images by mutableStateOf<Map<String, ByteArray>>(emptyMap())
    private set
  private val requestedImages = mutableSetOf<String>()

  init {
    load()
  }

  fun load() {
    loading = true
    error = null
    viewModelScope.launch {
      try {
        val loaded = graphQlClient.receipt(receiptId)
        if (loaded == null) {
          notFound = true
        } else {
          apply(loaded, resetSelection = true)
          if (loaded.unseen) markSeen()
        }
      } catch (e: Exception) {
        error = e.toUiText()
      } finally {
        loading = false
      }
    }
  }

  /** Zhasne odznak a notifikaci — vlastník účtenku viděl. Selhání nevadí, zkusí se příště. */
  private fun markSeen() {
    viewModelScope.launch {
      runCatching { graphQlClient.markReceiptSeen(receiptId) }
        .onSuccess { receipt = receipt?.copy(unseen = false) }
    }
  }

  private fun apply(updated: Receipt, resetSelection: Boolean) {
    receipt = updated
    val suggested = updated.lines.filter { it.status == "SUGGESTED" }.map { it.id }.toSet()
    selected = if (resetSelection) suggested else selected.intersect(suggested)
  }

  private fun replaceLine(line: ReceiptLine) {
    val current = receipt ?: return
    receipt = current.copy(lines = current.lines.map { if (it.id == line.id) line else it })
    selected = if (line.status == "SUGGESTED") selected + line.id else selected - line.id
  }

  fun toggleSelected(lineId: String) {
    selected = if (lineId in selected) selected - lineId else selected + lineId
  }

  val confirmableCount: Int
    get() = receipt?.lines?.count { it.status == "SUGGESTED" && it.id in selected } ?: 0

  // --- hlavička ---

  fun confirmSuggestedStore() {
    receipt?.suggestedStore?.let { onStoreSelected(it) }
  }

  fun onStoreQueryChange(query: String) {
    storeQuery = query
    storeSearchJob?.cancel()
    if (query.isBlank()) {
      storeSuggestions = emptyList()
      storeSearching = false
      return
    }
    storeSearchJob = viewModelScope.launch {
      delay(SEARCH_DEBOUNCE_MS)
      storeSearching = true
      try {
        storeSuggestions = graphQlClient.searchStores(query = query).items
      } catch (e: Exception) {
        // Našeptávač nesmí blokovat — uživatel může psát dál nebo obchod založit.
      } finally {
        storeSearching = false
      }
    }
  }

  fun onStoreSelected(store: Store) {
    storeSearchJob?.cancel()
    storeEditing = false
    storeQuery = ""
    storeSuggestions = emptyList()
    updateHeader(storeId = store.id)
  }

  fun setPurchasedAt(value: OffsetDateTime) {
    updateHeader(purchasedAt = value.toString())
  }

  private fun updateHeader(storeId: String? = null, purchasedAt: String? = null) {
    headerSaving = true
    actionError = null
    viewModelScope.launch {
      try {
        // Jiný obchod = server znovu spáruje řádky jiným rozsahem označení, výběr se přepočítá.
        apply(graphQlClient.updateReceiptHeader(receiptId, storeId, purchasedAt), resetSelection = storeId != null)
      } catch (e: Exception) {
        actionError = e.toUiText()
      } finally {
        headerSaving = false
      }
    }
  }

  // --- řádky ---

  fun openSuggestions(line: ReceiptLine) {
    suggestionLine = line
    suggestionQuery = ""
    suggestionItems = emptyList()
    loadSuggestions(line.id, null, debounce = false)
  }

  fun closeSuggestions() {
    suggestionJob?.cancel()
    suggestionLine = null
  }

  fun onSuggestionQueryChange(query: String) {
    suggestionQuery = query
    val line = suggestionLine ?: return
    loadSuggestions(line.id, query.ifBlank { null }, debounce = true)
  }

  private fun loadSuggestions(lineId: String, query: String?, debounce: Boolean) {
    suggestionJob?.cancel()
    suggestionJob = viewModelScope.launch {
      if (debounce) delay(SEARCH_DEBOUNCE_MS)
      suggestionsLoading = true
      suggestionsError = null
      try {
        suggestionItems = graphQlClient.receiptLineSuggestions(lineId, query)
      } catch (e: Exception) {
        suggestionsError = e.toUiText()
      } finally {
        suggestionsLoading = false
      }
    }
  }

  fun chooseProduct(product: ProductSummary) {
    val line = suggestionLine ?: return
    closeSuggestions()
    match(line.id, product.id, newProduct = false)
  }

  fun startNewProduct(line: ReceiptLine) {
    closeSuggestions()
    newProductLineId = line.id
  }

  /** Návrat z formuláře zboží — [isNew] rozliší nově založené od vybraného existujícího. */
  fun onProductFormResult(productId: String, isNew: Boolean) {
    val lineId = newProductLineId ?: return
    newProductLineId = null
    match(lineId, productId, newProduct = isNew)
  }

  private fun match(lineId: String, productId: String, newProduct: Boolean) =
    lineAction(lineId) { graphQlClient.matchReceiptLine(lineId, productId, newProduct) }

  fun ignore(line: ReceiptLine) = lineAction(line.id) { graphQlClient.ignoreReceiptLine(line.id) }

  fun unmatch(line: ReceiptLine) = lineAction(line.id) { graphQlClient.unmatchReceiptLine(line.id) }

  private fun lineAction(lineId: String, action: suspend () -> ReceiptLine) {
    busyLineIds = busyLineIds + lineId
    actionError = null
    viewModelScope.launch {
      try {
        replaceLine(action())
      } catch (e: Exception) {
        actionError = e.toUiText()
      } finally {
        busyLineIds = busyLineIds - lineId
      }
    }
  }

  fun confirm() {
    val current = receipt ?: return
    val lineIds = current.lines.filter { it.status == "SUGGESTED" && it.id in selected }.map { it.id }
    if (lineIds.isEmpty() || confirming) return
    confirming = true
    actionError = null
    viewModelScope.launch {
      try {
        val result = graphQlClient.confirmReceiptLines(receiptId, lineIds)
        apply(result.receipt, resetSelection = true)
        confirmResult = result
      } catch (e: Exception) {
        actionError = e.toUiText()
      } finally {
        confirming = false
      }
    }
  }

  fun dismissConfirmResult() {
    confirmResult = null
  }

  fun delete() {
    actionError = null
    viewModelScope.launch {
      try {
        graphQlClient.deleteReceipt(receiptId)
        deleted = true
      } catch (e: Exception) {
        actionError = e.toUiText()
      }
    }
  }

  fun loadImage(path: String) {
    if (!requestedImages.add(path)) return
    viewModelScope.launch {
      runCatching { mediaClient.receiptImage(path) }
        .onSuccess { images = images + (path to it) }
        // Při chybě jde obrázek zkusit znovu při dalším zobrazení.
        .onFailure { requestedImages.remove(path) }
    }
  }
}
