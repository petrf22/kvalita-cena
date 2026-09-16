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
import cz.kvalitacena.ui.settings.LastStoreStore
import cz.kvalitacena.ui.settings.PriceEntryVisibilityStore
import cz.kvalitacena.ui.settings.NearbySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STORE_SEARCH_DEBOUNCE_MS = 300L

/** Obrazovky 2–4 flow "sken → cena → výběr provozovny → odeslání". */
class PriceEntryViewModel(
  private val graphQlClient: GraphQlClient,
  private val target: PriceEntryTarget,
  private val countryStore: CountryStore,
  private val visibilityStore: PriceEntryVisibilityStore,
  private val lastStoreStore: LastStoreStore,
  private val nearbySettings: NearbySettings,
) : ViewModel() {

  var loading by mutableStateOf(true)
    private set
  var product by mutableStateOf<Product?>(null)
    private set
  var notFound by mutableStateOf(false)
    private set
  /** OFF katalog je teď nedostupný (výpadek/rate limit) — jiná hláška než "neznáme ho" pod notFound. */
  var offUnavailable by mutableStateOf(false)
    private set

  // Sekce "Zadat cenu" je schovaná za tlačítkem, dokud si uživatel jednou úspěšně nezapíše
  // cenu (viz PriceEntryVisibilityStore) — appka tak slouží stejně dobře lidem, co jen hledají
  // ceny poblíž. Rozbalení tlačítkem na TÉHLE obrazovce se nikam neukládá, jen sbalení/úspěšný
  // zápis (viz expandPriceEntry/hidePriceEntry/submit).
  var priceEntryExpanded by mutableStateOf(visibilityStore.expandedByDefault)
    private set

  // Automatická nabídka okolí i ruční hledání plní stejný seznam. Psaní ruší předchozí
  // výběr i probíhající hledání, aby se cena neposlala do jiné prodejny.
  var storeQuery by mutableStateOf("")
    private set
  var storeSuggestions by mutableStateOf<List<Store>>(emptyList())
    private set
  var storeSearching by mutableStateOf(false)
    private set
  var selectedStore by mutableStateOf<Store?>(null)
    private set
  /** Ručně vybraný obchod nepatří do rozsahu lokálního zboží (product.catalogScope) — blokuje odeslání. */
  var storeScopeMismatch by mutableStateOf(false)
    private set
  /** Varianta názvu, přes kterou uživatel vybral existující položku ve formuláři. */
  var productAlias by mutableStateOf<String?>(null)
    private set
  private var storeSearchJob: Job? = null

  var locating by mutableStateOf(false)
    private set
  var locationError by mutableStateOf<UiText?>(null)
    private set

  var rememberedStoreLoaded by mutableStateOf(false)
    private set
  var nearbyAttempted = false
    private set
  var storeSearchCompleted by mutableStateOf(false)
    private set
  var nearbyResults by mutableStateOf(false)
    private set
  private var selectionRevision = 0
  private var nearbyJob: Job? = null

  // Seznam řádků "(druh ceny, částka)" — u regálu bývá cena napsaná i dvakrát/třikrát (běžná,
  // klubová, množstevní), viz PriceRow. Vždy aspoň jeden řádek (removePriceRow ho neodebere).
  private var nextRowId = 1L
  var priceRows by mutableStateOf(listOf(PriceRow(id = nextRowId++)))
    private set

  // Jen pro váhové zboží (product.isVariableWeight) — jinak zůstává PACKAGE, viz backend
  // PriceObservationService (PER_KG/PER_L znamená "cena na cedulce je už za kg/l").
  var quantityBasis by mutableStateOf("PACKAGE")

  // Kdy uživatel cenu viděl — volitelné, ISO den (yyyy-MM-dd) jako promoValidFrom/To, prázdné =
  // "teď" (server dosadí now()). Web protějšek: price-entry-form.ts observedAt.
  var observedAt by mutableStateOf("")

  var submitting by mutableStateOf(false)
    private set
  var submitSuccess by mutableStateOf(false)
    private set
  var submitError by mutableStateOf<UiText?>(null)
    private set

  val canSubmit: Boolean
    get() = selectedStore != null && arePriceRowsValid(priceRows) && !submitting && !storeScopeMismatch

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
    loadRememberedStore()
  }

  private fun loadProduct() {
    loading = true
    viewModelScope.launch {
      try {
        when (target) {
          is PriceEntryTarget.ByBarcode -> {
            // productByCode (jen vlastní katalog) nahrazeno productLookupByCode, ať appka
            // zkusí i Open Food Facts — ProductFormScreen si OFF kandidáta při "Založit zboží"
            // dotáhne znovu ze stejné cache (GraphQlClient.productLookupByCode), druhé volání
            // je zdarma.
            val result = graphQlClient.productLookupByCode(target.barcode)
            when (result.status) {
              "EXISTING" -> {
                product = result.product
                notFound = result.product == null
              }
              "OFF_UNAVAILABLE" -> {
                notFound = true
                offUnavailable = true
              }
              else -> notFound = true // NOT_FOUND i OFF_CANDIDATE
            }
          }
          is PriceEntryTarget.ById -> {
            product = graphQlClient.productById(target.productId)
            notFound = product == null
          }
        }
        discardIncompatibleStore()
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
  fun onNewProductCreated(newProduct: Product, alias: String? = null) {
    product = newProduct
    productAlias = alias?.trim()?.ifBlank { null }
    notFound = false
    discardIncompatibleStore()
  }

  private fun loadRememberedStore() {
    val id = lastStoreStore.rememberedId()
    if (id == null) {
      rememberedStoreLoaded = true
      return
    }
    val revision = selectionRevision
    viewModelScope.launch {
      try {
        graphQlClient.storeById(id)?.let { store ->
          if (revision == selectionRevision && (product == null || productAvailableAtStore(product!!, store))) selectStore(store)
        } ?: lastStoreStore.clear()
      } catch (e: Exception) {
        // Výpadek načtení posledního obchodu nesmí blokovat ruční výběr.
      } finally {
        rememberedStoreLoaded = true
      }
    }
  }

  private fun productAvailableAtStore(product: Product, store: Store): Boolean = when (product.catalogScope) {
    "CHAIN" -> product.scopeChain?.id == store.chain?.id
    "STORE" -> product.scopeStore?.id == store.id
    else -> true
  }

  private fun discardIncompatibleStore() {
    val currentProduct = product ?: return
    val store = selectedStore ?: return
    if (!productAvailableAtStore(currentProduct, store)) {
      selectedStore = null
      storeQuery = ""
      storeScopeMismatch = false
    }
  }

  fun onStoreQueryChange(query: String) {
    selectionRevision++
    storeQuery = query
    selectedStore = null
    storeScopeMismatch = false
    storeSearchJob?.cancel()
    nearbyJob?.cancel()
    locating = false
    nearbyResults = false
    locationError = null
    storeSuggestions = emptyList()
    storeSearchCompleted = false
    storeSearching = query.isNotBlank()
    if (query.isBlank()) return
    val revision = selectionRevision
    storeSearchJob = viewModelScope.launch {
      try {
        delay(STORE_SEARCH_DEBOUNCE_MS)
        storeSuggestions = graphQlClient.searchStores(query = query.trim()).items
        storeSearchCompleted = true
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        locationError = e.toUiText()
      } finally {
        if (revision == selectionRevision) storeSearching = false
      }
    }
  }

  fun onStoreSelected(store: Store) {
    selectionRevision++
    storeSearchJob?.cancel()
    nearbyJob?.cancel()
    storeSearching = false
    locating = false
    locationError = null
    selectStore(store)
    // Nekompatibilní obchod se rovnou neschová (na rozdíl od discardIncompatibleStore při
    // změně produktu) — uživatel ho právě vybral ručně, appka jen zablokuje odeslání a napíše
    // proč, ať mu výběr nezmizí beze stopy.
    storeScopeMismatch = product?.let { !productAvailableAtStore(it, store) } ?: false
    if (!storeScopeMismatch) lastStoreStore.remember(store.id)
  }

  private fun selectStore(store: Store) {
    selectedStore = store
    storeQuery = storeLabel(store, countryStore.country)
  }

  /** Návrat z formuláře nového obchodu (StoreFormScreen) — rovnou ho vybrat. */
  fun onNewStoreCreated(store: Store) {
    onStoreSelected(store)
    storeSuggestions = emptyList()
    storeSearchCompleted = false
    nearbyResults = false
  }

  fun onLocationResolved(lat: Double, lon: Double, revision: Int) {
    if (revision != selectionRevision) return
    locationError = null
    nearbyJob = viewModelScope.launch {
      try {
        storeSuggestions = graphQlClient.nearbyStores(lat, lon, nearbySettings.radiusMeters / 1000.0)
        // Zapamatovanou vzdálenou prodejnu nepotvrzovat jako dnešní místo nákupu.
        if (selectedStore != null && storeSuggestions.none { it.id == selectedStore?.id }) {
          selectedStore = null
          storeQuery = ""
          storeScopeMismatch = false
        }
        storeSearchCompleted = true
        nearbyResults = true
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        locationError = e.toUiText()
      } finally {
        if (revision == selectionRevision) locating = false
      }
    }
  }

  fun onLocationUnavailable(revision: Int? = null) {
    if (revision != null && revision != selectionRevision) return
    locating = false
    locationError = UiText.Res(R.string.nearby_without_location)
  }

  fun shouldFindNearbyAutomatically(): Boolean = !nearbyAttempted && selectionRevision == 0

  fun startLocating(): Int {
    nearbyAttempted = true
    selectionRevision++
    storeSearchJob?.cancel()
    nearbyJob?.cancel()
    storeSearching = false
    storeSearchCompleted = false
    storeSuggestions = emptyList()
    locating = true
    locationError = null
    return selectionRevision
  }

  fun submit() {
    val currentProduct = product ?: return
    val storeId = selectedStore?.id ?: return
    if (!canSubmit) return

    submitting = true
    submitError = null
    submitSuccess = false
    viewModelScope.launch {
      try {
        graphQlClient.submitObservations(
          SubmitObservationsInput(
            productId = currentProduct.id,
            storeId = storeId,
            productAlias = productAlias,
            quantityBasis = if (currentProduct.isVariableWeight) quantityBasis else "PACKAGE",
            observedAt = toObservedAtIso(observedAt),
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
