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
 * nikdy nerozhoduje"): řádky, které server spároval jistě (SUGGESTED podle kódu nebo
 * označení), jsou předem zaškrtnuté, ale cenu zapíše až „Potvrdit spárované". Odhad podle
 * podobnosti názvu (FUZZY) předem zaškrtnutý není — pravidla výběru jsou
 * v `ReceiptLineSelection.kt`.
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

  /** Řádky zaškrtnuté k potvrzení — výchozí jsou SUGGESTED kromě odhadů ([defaultSelectedLineIds]). */
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
    selected = selectionAfterLoad(selected, updated.lines, reset = resetSelection)
  }

  private fun replaceLine(line: ReceiptLine) {
    val current = receipt ?: return
    receipt = current.copy(lines = current.lines.map { if (it.id == line.id) line else it })
    selected = selectionAfterLineChange(selected, line)
  }

  fun toggleSelected(lineId: String) {
    selected = if (lineId in selected) selected - lineId else selected + lineId
  }

  val confirmableCount: Int
    get() = receipt?.lines?.count { it.isConfirmable() && it.id in selected } ?: 0

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

  // --- řádky: spodní panel a průvodce ---

  /**
   * Řádek otevřený ve spodním panelu (ťuknutí na řádek nebo krok průvodce). Panel se při
   * návratu z formuláře nového zboží ukáže znovu, protože stav žije ve ViewModelu.
   */
  var sheetLineId by mutableStateOf<String?>(null)
    private set

  /** Panel ukazuje hledání zboží — u nespárovaného řádku hned, u návrhu po „Jiné zboží". */
  var sheetSearching by mutableStateOf(false)
    private set

  /** Průvodce „Doplnit nerozpoznané" — fronta zachycená při startu ([guideQueue]). */
  var guideActive by mutableStateOf(false)
    private set
  private var guide: List<String> = emptyList()

  val sheetLine: ReceiptLine?
    get() = sheetLineId?.let { id -> receipt?.lines?.find { it.id == id } }

  /** „3 / 7" v průvodci; mimo průvodce null. */
  val guidePosition: Pair<Int, Int>?
    get() = sheetLineId?.takeIf { guideActive }?.let { id -> guide.indexOf(id) + 1 to guide.size }

  /** Kolik řádků potřebuje člověka — počet na tlačítku průvodce. */
  val guideCount: Int
    get() = receipt?.lines?.let { guideQueue(it).size } ?: 0

  fun openLine(line: ReceiptLine) {
    guideActive = false
    showLine(line)
  }

  fun startGuide() {
    val lines = receipt?.lines ?: return
    guide = guideQueue(lines)
    guideActive = true
    val first = nextInGuide(guide, null, lines)
    if (first == null) closeSheet() else lines.find { it.id == first }?.let(::showLine)
  }

  private fun showLine(line: ReceiptLine) {
    actionError = null
    sheetLineId = line.id
    val searching = line.group() == LineGroup.TO_RESOLVE
    sheetSearching = searching
    suggestionLine = line
    suggestionQuery = ""
    suggestionItems = emptyList()
    suggestionsError = null
    suggestionJob?.cancel()
    if (searching) loadSuggestions(line.id, null, debounce = false)
  }

  /** „Jiné zboží" u návrhu — ukáže hledání místo navrženého zboží. */
  fun showOtherProducts() {
    val line = sheetLine ?: return
    sheetSearching = true
    if (suggestionItems.isEmpty()) loadSuggestions(line.id, null, debounce = false)
  }

  fun closeSheet() {
    sheetLineId = null
    guideActive = false
    closeSuggestions()
  }

  /**
   * Další krok: v průvodci další řádek, který pořád potřebuje člověka; mimo průvodce zavře
   * panel. Volá se hned po akci — aktuální řádek se přeskočí podle pozice ve frontě, takže
   * nevadí, že odpověď serveru ještě nedorazila.
   */
  fun advance() {
    if (!guideActive) {
      closeSheet()
      return
    }
    val lines = receipt?.lines.orEmpty()
    val next = nextInGuide(guide, sheetLineId, lines)?.let { id -> lines.find { it.id == id } }
    if (next == null) closeSheet() else showLine(next)
  }

  /** „Ano, je to ono" — návrh se zaškrtne k potvrzení. */
  fun acceptSuggestion(line: ReceiptLine) {
    selected = selected + line.id
    advance()
  }

  /**
   * Posun z panelu až po uložení akce: při výpadku sítě (obchod bez signálu) nebo chybě serveru
   * panel zůstane na řádku a ukáže chybu ([actionError]), ať jde akci hned zopakovat. Když
   * uživatel mezitím panel přepnul jinam, nic se neposouvá.
   */
  private fun advanceAfter(lineId: String): () -> Unit = {
    if (sheetLineId == lineId) advance()
  }

  /** Ignorovat z panelu (záloha, taška, sleva) a pokračovat. */
  fun ignoreFromSheet(line: ReceiptLine) =
    lineAction(line.id, advanceAfter(line.id)) { graphQlClient.ignoreReceiptLine(line.id) }

  /** Vrátit ignorovaný řádek mezi nespárované z panelu. */
  fun restoreFromSheet(line: ReceiptLine) =
    lineAction(line.id, advanceAfter(line.id)) { graphQlClient.unmatchReceiptLine(line.id) }

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

  /** Znovu načíst návrhy po chybě (výpadek sítě v obchodě). */
  fun retrySuggestions() {
    val line = suggestionLine ?: return
    loadSuggestions(line.id, suggestionQuery.ifBlank { null }, debounce = false)
  }

  fun chooseProduct(product: ProductSummary) {
    val line = suggestionLine ?: return
    match(line.id, product.id, newProduct = false, onSuccess = advanceAfter(line.id))
  }

  /** Formulář nového zboží se otevře jako jiná obrazovka; panel zůstane na řádku do návratu. */
  fun startNewProduct(line: ReceiptLine) {
    newProductLineId = line.id
  }

  /** Návrat z formuláře zboží — [isNew] rozliší nově založené od vybraného existujícího. */
  fun onProductFormResult(productId: String, isNew: Boolean) {
    val lineId = newProductLineId ?: return
    newProductLineId = null
    match(lineId, productId, newProduct = isNew, onSuccess = advanceAfter(lineId))
  }

  private fun match(lineId: String, productId: String, newProduct: Boolean, onSuccess: () -> Unit = {}) =
    lineAction(lineId, onSuccess) { graphQlClient.matchReceiptLine(lineId, productId, newProduct) }

  fun ignore(line: ReceiptLine) = lineAction(line.id) { graphQlClient.ignoreReceiptLine(line.id) }

  fun unmatch(line: ReceiptLine) = lineAction(line.id) { graphQlClient.unmatchReceiptLine(line.id) }

  private fun lineAction(lineId: String, onSuccess: () -> Unit = {}, action: suspend () -> ReceiptLine) {
    busyLineIds = busyLineIds + lineId
    actionError = null
    viewModelScope.launch {
      try {
        replaceLine(action())
        busyLineIds = busyLineIds - lineId
        onSuccess()
      } catch (e: Exception) {
        actionError = e.toUiText()
      } finally {
        busyLineIds = busyLineIds - lineId
      }
    }
  }

  fun confirm() {
    val current = receipt ?: return
    // Ručně zaškrtnutý odhad (FUZZY) jde potvrdit stejně jako jiný návrh.
    val lineIds = current.lines.filter { it.isConfirmable() && it.id in selected }.map { it.id }
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
