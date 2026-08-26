package cz.kvalitacena.ui.price

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.R
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.Product
import cz.kvalitacena.network.Store
import cz.kvalitacena.network.SubmitObservationsInput
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.common.storeLabel
import cz.kvalitacena.ui.common.toUiText
import cz.kvalitacena.ui.settings.CountryStore
import cz.kvalitacena.ui.settings.PriceEntryVisibilityStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STORE_SEARCH_DEBOUNCE_MS = 300L

/** Obrazovky 2–4 flow "sken → cena → výběr provozovny → odeslání" (viz plán projektu). */
class PriceEntryViewModel(
  private val graphQlClient: GraphQlClient,
  private val target: PriceEntryTarget,
  private val countryStore: CountryStore,
  private val visibilityStore: PriceEntryVisibilityStore,
) : ViewModel() {

  var loading by mutableStateOf(true)
    private set
  var product by mutableStateOf<Product?>(null)
    private set
  var notFound by mutableStateOf(false)
    private set

  // Sekce "Zadat cenu" je schovaná za tlačítkem, dokud si uživatel jednou úspěšně nezapíše
  // cenu (viz PriceEntryVisibilityStore) — appka tak slouží stejně dobře lidem, co jen hledají
  // ceny poblíž. Rozbalení tlačítkem na TÉHLE obrazovce se nikam neukládá, jen sbalení/úspěšný
  // zápis (viz expandPriceEntry/hidePriceEntry/submit).
  var priceEntryExpanded by mutableStateOf(visibilityStore.expandedByDefault)
    private set

  // Obchod se dá vybrat třemi cestami — napsat název/město (storeQuery → searchStores),
  // "Najít v okolí" (nearbyStores, dosavadní chování) nebo založit nový. Všechny tři plní
  // stejný `storeSuggestions`, ať má obrazovka jeden zdroj pravdy pro nabídku (StorePicker).
  var storeQuery by mutableStateOf("")
  var storeSuggestions by mutableStateOf<List<Store>>(emptyList())
    private set
  var storeSearching by mutableStateOf(false)
    private set
  var selectedStore by mutableStateOf<Store?>(null)
    private set
  private var storeSearchJob: Job? = null

  var locating by mutableStateOf(false)
    private set
  var locationError by mutableStateOf<UiText?>(null)
    private set

  // Roste při každém úspěšném "Najít v okolí" — StorePicker/SearchableDropdown ho použije jako
  // expandSignal, aby nabídku otevřel i bez psaní (dřív appka nabídku potichu naplnila a sama
  // vybrala první obchod, viz onLocationResolved níž).
  var nearbyStoresSignal by mutableStateOf(0)
    private set

  // Seznam řádků "(druh ceny, částka)" — u regálu bývá cena napsaná i dvakrát/třikrát (běžná,
  // klubová, množstevní), viz PriceRow. Vždy aspoň jeden řádek (removePriceRow ho neodebere).
  private var nextRowId = 1L
  var priceRows by mutableStateOf(listOf(PriceRow(id = nextRowId++)))
    private set

  // Jen pro váhové zboží (product.isVariableWeight) — jinak zůstává PACKAGE, viz backend
  // PriceObservationService (PER_KG/PER_L znamená "cena na cedulce je už za kg/l").
  var quantityBasis by mutableStateOf("PACKAGE")

  var submitting by mutableStateOf(false)
    private set
  var submitSuccess by mutableStateOf(false)
    private set
  var submitError by mutableStateOf<UiText?>(null)
    private set

  val canSubmit: Boolean
    get() = selectedStore != null && arePriceRowsValid(priceRows) && !submitting

  fun addPriceRow() {
    priceRows = priceRows + PriceRow(id = nextRowId++, priceKind = firstAvailablePriceKind(priceRows))
  }

  /** Nikdy neodebere poslední řádek — formulář musí vždycky mít aspoň jednu cenu k zápisu. */
  fun removePriceRow(id: Long) {
    if (priceRows.size <= 1) return
    priceRows = priceRows.filter { it.id != id }
  }

  fun updatePriceRow(id: Long, transform: (PriceRow) -> PriceRow) {
    priceRows = priceRows.map { if (it.id == id) transform(it) else it }
  }

  init {
    loadProduct()
  }

  private fun loadProduct() {
    loading = true
    viewModelScope.launch {
      try {
        val found = when (target) {
          is PriceEntryTarget.ByBarcode -> graphQlClient.productByCode(target.barcode)
          is PriceEntryTarget.ById -> graphQlClient.productById(target.productId)
        }
        product = found
        notFound = found == null
      } catch (e: Exception) {
        notFound = true
      } finally {
        loading = false
      }
    }
  }

  /** Naskenovaný/zadaný kód pro předvyplnění formuláře nového zboží — null u vstupu z detailu. */
  fun barcodeForNewProduct(): String? = (target as? PriceEntryTarget.ByBarcode)?.barcode

  /** Kód pro tlačítko "Hledat ceny tohoto zboží" — u vstupu z detailu appka kód nezná, hledá se podle názvu. */
  fun searchQueryForPrices(): String = barcodeForNewProduct() ?: product?.name.orEmpty()

  /** Klik na tlačítko "Zadat cenu" — rozbalí sekci jen pro tuhle obrazovku, bez zápisu preference. */
  fun expandPriceEntry() {
    priceEntryExpanded = true
  }

  /** Klik na "Skrýt zadání ceny" — na rozdíl od expandPriceEntry i ukládá, že příští sken má být rovnou sbalený. */
  fun hidePriceEntry() {
    priceEntryExpanded = false
    visibilityStore.select(false)
  }

  /** Návrat z formuláře nového zboží (ProductFormScreen) — obrazovka rovnou pokračuje se zápisem ceny. */
  fun onNewProductCreated(newProduct: Product) {
    product = newProduct
    notFound = false
  }

  fun onStoreQueryChange(query: String) {
    storeQuery = query
    storeSearchJob?.cancel()
    if (query.isBlank()) {
      storeSuggestions = emptyList()
      return
    }
    storeSearchJob = viewModelScope.launch {
      delay(STORE_SEARCH_DEBOUNCE_MS)
      storeSearching = true
      try {
        storeSuggestions = graphQlClient.searchStores(query = query).items
      } catch (e: Exception) {
        // Chyba našeptávače nesmí blokovat zápis — "Najít v okolí" a ruční výběr fungují dál.
      } finally {
        storeSearching = false
      }
    }
  }

  fun onStoreSelected(store: Store) {
    selectedStore = store
    storeQuery = storeLabel(store, countryStore.country)
  }

  /** Návrat z formuláře nového obchodu (StoreFormScreen) — rovnou ho vybrat. */
  fun onNewStoreCreated(store: Store) {
    selectedStore = store
    storeQuery = storeLabel(store, countryStore.country)
    storeSuggestions = emptyList()
  }

  fun onLocationResolved(lat: Double, lon: Double) {
    locating = true
    locationError = null
    viewModelScope.launch {
      try {
        val stores = graphQlClient.nearbyStores(lat, lon)
        storeSuggestions = stores
        if (stores.isEmpty()) {
          locationError = UiText.Res(R.string.store_picker_no_nearby_stores)
        } else {
          // Dřív appka potichu vybrala stores.first() — uživatel viděl výsledek "Najít v
          // okolí" jen jako vybraný obchod, ostatní nálezy nešly vidět/zvolit (viz plán
          // projektu, "nefunguje mi výběr obchodu z mapy"). Teď appka jen otevře nabídku
          // (a mapu, StorePicker.StoreMap) a výběr nechá na uživateli.
          nearbyStoresSignal++
        }
      } catch (e: Exception) {
        locationError = e.toUiText()
      } finally {
        locating = false
      }
    }
  }

  fun onLocationUnavailable() {
    locating = false
    locationError = UiText.Res(R.string.price_entry_location_unavailable)
  }

  fun startLocating() {
    locating = true
    locationError = null
  }

  fun submit() {
    val currentProduct = product ?: return
    val storeId = selectedStore?.id ?: return
    if (!arePriceRowsValid(priceRows)) return

    submitting = true
    submitError = null
    submitSuccess = false
    viewModelScope.launch {
      try {
        graphQlClient.submitObservations(
          SubmitObservationsInput(
            productId = currentProduct.id,
            storeId = storeId,
            quantityBasis = if (currentProduct.isVariableWeight) quantityBasis else "PACKAGE",
            prices = toObservationPriceInputs(priceRows),
          ),
        )
        // Obrazovka po úspěchu mizí (návrat na sken), takže není potřeba dohánět stav
        // (obnova produktu, čištění pole) — ViewModel se zahodí spolu s ní.
        submitSuccess = true
        // Uživatel právě zapsal cenu — příští sken ať sekci rovnou rozbalí (PriceEntryVisibilityStore).
        visibilityStore.select(true)
      } catch (e: Exception) {
        submitError = e.toUiText()
      } finally {
        submitting = false
      }
    }
  }
}
