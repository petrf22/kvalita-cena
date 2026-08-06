package cz.kvalitacena.ui.product

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.network.Category
import cz.kvalitacena.network.CreateProductInput
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.Product
import cz.kvalitacena.network.ProductSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SUGGESTIONS_DEBOUNCE_MS = 300L

/**
 * Založení zboží — s naskenovaným EANem i bez něj. Bezkódové zboží (žádný kód na obalu, jen
 * "pečivo za 45 Kč" na účtence, nebo podniková prodejna zemědělského družstva bez EANu)
 * vznikne jako druhová položka (docs/reputace.md, "Zboží bez čárového kódu") — server ji
 * založí jako DRAFT/isGeneric a confidence zastropuje na MEDIUM, appka tu nic z toho neřeší.
 *
 * `barcode` z konstruktoru je naskenovaný/zadaný kód, který uživatel nenašel (viz
 * PriceEntryScreen "Založit zboží") — předvyplní pole kódu, ale jde ho smazat pro bezkódový zápis.
 */
class ProductFormViewModel(
  private val graphQlClient: GraphQlClient,
  barcode: String?,
) : ViewModel() {

  var name by mutableStateOf("")
  var suggestions by mutableStateOf<List<ProductSummary>>(emptyList())
    private set
  var suggestionsLoading by mutableStateOf(false)
    private set
  private var suggestionsJob: Job? = null

  var categories by mutableStateOf<List<Category>>(emptyList())
    private set
  var selectedCategoryId by mutableStateOf<String?>(null)

  var brandName by mutableStateOf("")
  var unitBase by mutableStateOf("COUNT")
  var netContentValue by mutableStateOf("")
  var piecesInPack by mutableStateOf("")
  var isVariableWeight by mutableStateOf(false)
  var code by mutableStateOf(barcode.orEmpty())

  var saving by mutableStateOf(false)
    private set
  var saveError by mutableStateOf<String?>(null)
    private set
  var created by mutableStateOf<Product?>(null)
    private set
  var usingExisting by mutableStateOf(false)
    private set

  init {
    viewModelScope.launch {
      try {
        categories = graphQlClient.categories()
      } catch (e: Exception) {
        // Formulář jde vyplnit, i když se číselník kategorií nenačetl — jen se pak nedá
        // uložit (kategorie je povinná), chyba se ukáže při pokusu o odeslání.
      }
    }
  }

  fun onNameChange(value: String) {
    name = value
    suggestionsJob?.cancel()
    if (value.isBlank()) {
      suggestions = emptyList()
      return
    }
    suggestionsJob = viewModelScope.launch {
      delay(SUGGESTIONS_DEBOUNCE_MS)
      suggestionsLoading = true
      try {
        suggestions = graphQlClient.productSuggestions(value)
      } catch (e: Exception) {
        // Chyba v nabídce nesmí blokovat založení nového zboží.
      } finally {
        suggestionsLoading = false
      }
    }
  }

  /** Uživatel si vybral existující nabídnutou položku místo založení nové (docs/reputace.md). */
  fun useExisting(summary: ProductSummary) {
    usingExisting = true
    saving = true
    saveError = null
    viewModelScope.launch {
      try {
        created = graphQlClient.productById(summary.id)
      } catch (e: Exception) {
        saveError = "Nepodařilo se načíst vybrané zboží, zkus to prosím znovu."
        usingExisting = false
      } finally {
        saving = false
      }
    }
  }

  fun submit() {
    val categoryId = selectedCategoryId ?: return
    if (name.isBlank()) return

    saving = true
    saveError = null
    viewModelScope.launch {
      try {
        val input = CreateProductInput(
          name = name.trim(),
          brandName = brandName.trim().ifBlank { null },
          categoryId = categoryId,
          unitBase = unitBase,
          netContentValue = netContentValue.replace(',', '.').toDoubleOrNull(),
          netContentUom = impliedUom(),
          piecesInPack = piecesInPack.toIntOrNull(),
          isVariableWeight = isVariableWeight,
          code = code.trim().ifBlank { null },
        )
        created = graphQlClient.createProduct(input)
      } catch (e: Exception) {
        saveError = e.message ?: "Založení zboží se nepovedlo, zkus to prosím znovu."
      } finally {
        saving = false
      }
    }
  }

  /** Server dopočítá netContentBase ze základní jednotky, appka jen pošle odpovídající UOM. */
  private fun impliedUom(): String? = when (unitBase) {
    "MASS" -> "KG"
    "VOLUME" -> "L"
    "COUNT" -> "PCS"
    else -> null
  }
}
