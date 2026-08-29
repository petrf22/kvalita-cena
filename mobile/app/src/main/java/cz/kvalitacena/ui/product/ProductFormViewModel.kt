package cz.kvalitacena.ui.product

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.AppContainer
import cz.kvalitacena.network.Category
import cz.kvalitacena.network.CreateProductFromOffInput
import cz.kvalitacena.network.CreateProductInput
import cz.kvalitacena.network.ExternalProductCandidate
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.Product
import cz.kvalitacena.network.ProductSummary
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.common.categoryBreadcrumb
import cz.kvalitacena.ui.common.normalizeCode
import cz.kvalitacena.ui.common.toUiText
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
    private set

  /** Text v hledacím poli kategorie — prázdný ukáže celý strom, jinak plochý filtr podle jména
   *  (ui/common/CategoryTree.kt, zrcadlo frontend shared/category-tree.ts). Po výběru appka
   *  sem dosadí breadcrumb vybrané kategorie, stejný vzor jako PriceEntryViewModel.storeQuery.
   *  Samotnou nabídku (`CategoryChoice`) skládá `ProductFormScreen` z `categoryChoicesFor` —
   *  potřebuje `LocalConfiguration.current.locales[0]` pro řazení sourozenců, což ViewModel
   *  mimo Compose kontext nemá (stejný důvod jako RelativeDate.kt/Money.kt). */
  var categoryQuery by mutableStateOf("")
    private set

  fun onCategoryQueryChange(query: String) {
    categoryQuery = query
  }

  fun onCategorySelected(category: Category) {
    selectedCategoryId = category.id
    categoryQuery = categoryBreadcrumb(category, categories)
  }

  var brandName by mutableStateOf("")
  var unitBase by mutableStateOf("COUNT")
  var netContentValue by mutableStateOf("")
  var piecesInPack by mutableStateOf("")
  var isVariableWeight by mutableStateOf(false)
  var code by mutableStateOf(barcode.orEmpty())

  var itemPhotoUri by mutableStateOf<Uri?>(null)
  var labelPhotoUri by mutableStateOf<Uri?>(null)

  var saving by mutableStateOf(false)
    private set
  /** Zboží se založilo, ale aspoň jednu vybranou fotku se nepodařilo nahrát — produkt v tu
   *  chvíli už existuje (docs/datovy-model.md, "fotky se nahrávají výhradně na existující
   *  záznam"), appka jen upozorní, ne zablokuje pokračování. */
  var photoUploadFailed by mutableStateOf(false)
    private set
  var saveError by mutableStateOf<UiText?>(null)
    private set
  var created by mutableStateOf<Product?>(null)
    private set
  var usingExisting by mutableStateOf(false)
    private set

  /** Nabídnutý OFF kandidát pro banner nad formulářem — null, dokud appka nic nenašla/nehledala. */
  var offCandidate by mutableStateOf<ExternalProductCandidate?>(null)
    private set
  /** Snímek předvyplněných hodnot (gramáž převedená na kg/l) — jen appka sama, ne pro UI. */
  private var offDefaults: OffDefaults? = null
  /** OFF kandidát dorazil dřív než číselník kategorií — kategorie se dopočítá, až categories() doběhne. */
  private var pendingCategoryId: String? = null

  init {
    viewModelScope.launch {
      try {
        categories = graphQlClient.categories()
        pendingCategoryId?.let { id -> categories.find { it.id == id }?.let(::onCategorySelected) }
      } catch (e: Exception) {
        // Formulář jde vyplnit, i když se číselník kategorií nenačetl — jen se pak nedá
        // uložit (kategorie je povinná), chyba se ukáže při pokusu o odeslání.
      }
    }
    // Naskenovaný/zadaný kód, který se v katalogu nenašel — zkusí, jestli ho nezná Open Food
    // Facts (productLookupByCode, cache v GraphQlClient — druhé volání po PriceEntryViewModel
    // je zdarma). Výpadek/nedostupnost OFF je tichý no-op, formulář zůstane prázdný a ručně
    // vyplnitelný.
    if (barcode != null) {
      viewModelScope.launch {
        try {
          val result = graphQlClient.productLookupByCode(barcode)
          if (result.status == "OFF_CANDIDATE" && result.candidate != null) {
            applyOffCandidate(result.candidate)
          }
        } catch (e: Exception) {
          // Tichý no-op — viz komentář výš.
        }
      }
    }
  }

  private data class OffDefaults(
    val name: String?,
    val brandName: String?,
    val categoryId: String?,
    val unitBase: String?,
    val netContentValue: Double?,
  )

  /** Gramáž/objem OFF nese v G/ML (OffNetContentConverter na backendu), appka vždy v kg/l. */
  private fun offDefaultsFrom(candidate: ExternalProductCandidate): OffDefaults {
    val netContentValue = when (candidate.netContentUom) {
      "G", "ML" -> candidate.netContentValue?.div(1000)
      "KG", "L" -> candidate.netContentValue
      else -> null
    }
    return OffDefaults(
      name = candidate.name,
      brandName = candidate.brandName,
      categoryId = candidate.category?.id,
      unitBase = candidate.unitBase,
      netContentValue = netContentValue,
    )
  }

  /** Předvyplní formulář z OFF kandidáta — nepřepisuje pole, která kandidát nemá (necháme
   *  prázdné pro ruční vyplnění). Snímek pro submit() si uloží stranou do offDefaults. */
  private fun applyOffCandidate(candidate: ExternalProductCandidate) {
    offCandidate = candidate
    val defaults = offDefaultsFrom(candidate)
    offDefaults = defaults
    defaults.name?.let { name = it }
    defaults.brandName?.let { brandName = it }
    defaults.unitBase?.let { unitBase = it }
    defaults.netContentValue?.let { netContentValue = it.toString() }
    defaults.categoryId?.let { id ->
      val category = categories.find { it.id == id }
      if (category != null) onCategorySelected(category) else pendingCategoryId = id
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
        saveError = e.toUiText()
        usingExisting = false
      } finally {
        saving = false
      }
    }
  }

  fun submit(context: Context) {
    val categoryId = selectedCategoryId ?: return
    if (name.isBlank()) return

    saving = true
    saveError = null
    photoUploadFailed = false
    viewModelScope.launch {
      try {
        val candidate = offCandidate
        val defaults = offDefaults
        // Kód se od nabídky kandidáta pořád musí shodovat — jinak uživatel kód smazal/přepsal
        // (bezkódová položka, jiné zboží) a appka musí uložit přes createProduct, ne
        // createProductFromOff (OFF hodnoty se nesmí zapsat do core.product jako vlastní).
        val product = if (candidate != null && defaults != null && codeMatchesOffCandidate(code, candidate.code)) {
          graphQlClient.createProductFromOff(buildOffInput(candidate, defaults, categoryId))
        } else {
          graphQlClient.createProduct(
            CreateProductInput(
              name = name.trim(),
              brandName = brandName.trim().ifBlank { null },
              categoryId = categoryId,
              unitBase = unitBase,
              netContentValue = netContentValue.replace(',', '.').toDoubleOrNull(),
              netContentUom = impliedUom(),
              piecesInPack = piecesInPack.toIntOrNull(),
              isVariableWeight = isVariableWeight,
              code = code.trim().ifBlank { null },
            ),
          )
        }
        // Zboží už existuje — fotky se nahrávají VÝHRADNĚ na existující záznam
        // (docs/datovy-model.md), teprve teď má appka kam je poslat. `created` se nastaví AŽ
        // po uploadu — ProductFormScreen na něj reaguje okamžitou navigací pryč
        // (LaunchedEffect(viewModel.created)), dřívější nastavení by upload utnulo.
        uploadPendingPhotos(context, product.id)
        created = product
      } catch (e: Exception) {
        saveError = e.toUiText()
      } finally {
        saving = false
      }
    }
  }

  /**
   * Nahraje vybrané fotky (fotka zboží první, pak etiketa) na právě založený produkt —
   * sekvenčně, ne najednou. Selhání jedné fotky nezastaví druhou ani neshodí založení zboží
   * (`photoUploadFailed`); produkt v tu chvíli už existuje, fotku jde doplnit později z jeho
   * detailu.
   */
  private suspend fun uploadPendingPhotos(context: Context, productId: String) {
    for (upload in pendingPhotoUploads(itemPhotoUri, labelPhotoUri)) {
      try {
        AppContainer.mediaClient.upload(context, "PRODUCT", productId, upload.value, kind = upload.kind)
      } catch (e: Exception) {
        photoUploadFailed = true
      }
    }
  }

  /**
   * Založení nad potvrzeným OFF kandidátem — pole, která uživatel nezměnil oproti offDefaults,
   * se posílají jako null, ať je dál dodává OFF a nevznikne zbytečný core.product_user_edit
   * patch. Gramáž/objem se posílá vždy spolu s jednotkou (viz netContentForOffSubmit).
   */
  private fun buildOffInput(
    candidate: ExternalProductCandidate,
    defaults: OffDefaults,
    categoryId: String,
  ): CreateProductFromOffInput {
    val trimmedName = name.trim()
    val trimmedBrand = brandName.trim().ifBlank { null }
    val currentNetContentValue = if (isVariableWeight) null else netContentValue.replace(',', '.').toDoubleOrNull()
    val (offNetContentValue, offNetContentUom) = netContentForOffSubmit(currentNetContentValue, defaults.netContentValue)
    return CreateProductFromOffInput(
      code = candidate.code,
      name = if (trimmedName == defaults.name) null else trimmedName,
      brandName = if (trimmedBrand == defaults.brandName) null else trimmedBrand,
      categoryId = if (categoryId == defaults.categoryId) null else categoryId,
      unitBase = unitBase,
      netContentValue = offNetContentValue,
      netContentUom = offNetContentUom,
      piecesInPack = piecesInPack.toIntOrNull(),
      isVariableWeight = isVariableWeight,
    )
  }

  /**
   * Gramáž/objem pro CreateProductFromOffInput — hodnota a jednotka se MUSÍ posílat vždy jako
   * dvojice, nikdy jen jedna z nich: server by jinak spočítal netContentBase ze staré OFF
   * hodnoty spárované s novou jednotkou z formuláře (u gramů vs. kg 1000× větší číslo). Shoda
   * s převedeným OFF defaultem (nebo nic nezadáno) → obojí null, ať hodnotu dál dodává OFF;
   * jinak (uživatel opravil, nebo OFF žádnou gramáž nedal) obojí z formuláře.
   */
  private fun netContentForOffSubmit(currentValue: Double?, defaultValue: Double?): Pair<Double?, String?> {
    val changed = currentValue != null &&
      (defaultValue == null || kotlin.math.abs(currentValue - defaultValue) >= 1e-9)
    return if (!changed) null to null else currentValue to impliedUom()
  }

  /** Naskenovaný/zadaný kód pořád patří k nabídnutému OFF kandidátovi — jinak uživatel kód
   *  smazal nebo přepsal (bezkódová položka, jiné zboží) a appka musí uložit přes createProduct,
   *  ne createProductFromOff. */
  private fun codeMatchesOffCandidate(code: String, candidateCode: String): Boolean {
    val normalized = normalizeCode(code)
    return normalized.isNotEmpty() && normalized == normalizeCode(candidateCode)
  }

  /** Server dopočítá netContentBase ze základní jednotky, appka jen pošle odpovídající UOM. */
  private fun impliedUom(): String? = when (unitBase) {
    "MASS" -> "KG"
    "VOLUME" -> "L"
    "COUNT" -> "PCS"
    else -> null
  }
}
