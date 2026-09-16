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
import cz.kvalitacena.ui.common.NearbyState
import cz.kvalitacena.ui.common.OsmState
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.common.storeLabel
import cz.kvalitacena.ui.common.toUiText
import cz.kvalitacena.ui.settings.CountryStore
import cz.kvalitacena.ui.settings.LastStoreStore
import cz.kvalitacena.ui.settings.NearbyRadius
import cz.kvalitacena.ui.settings.NearbyRadiusStore
import cz.kvalitacena.ui.settings.PriceEntryVisibilityStore
import cz.kvalitacena.ui.settings.widerNearbyRadius
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
  private val nearbyRadiusStore: NearbyRadiusStore,
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

  // Obchod se dá vybrat třemi cestami — automatickým hledáním v okolí (nearbyStores), napsáním
  // názvu/města (storeQuery → searchStores) nebo založením nového. Všechny tři plní stejný
  // `storeSuggestions`, ať má obrazovka jeden zdroj pravdy pro nabídku i mapu (StorePicker).
  var storeQuery by mutableStateOf("")
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

  // Obchody v okolí se od 2026-09 hledají SAMY hned po otevření (dřív až po stisku tlačítka
  // "Najít v okolí") — stav proto musí umět odlišit "ještě se nehledalo" od "nenašlo se nic",
  // a hlavně jednotlivé důvody, proč appka polohu nemá: u vypnuté polohy stačí poradit ji
  // zapnout, u nepovoleného oprávnění nabídnout dialog, u timeoutu zkusit znovu. Dřív se
  // všechno slilo do jedné hlášky, která uživateli neřekla, co má udělat (docs/design.md,
  // "Čtyři stavy každé obrazovky").
  var nearbyState by mutableStateOf<NearbyState>(NearbyState.Idle)
    private set

  /** Nabídka z OpenStreetMap — načítá se líně, teprve když vlastní katalog v okolí nic nemá. */
  var osmState by mutableStateOf<OsmState>(OsmState.Idle)
    private set

  /**
   * Okruh pro TUHLE obrazovku. Začíná na volbě z Nastavení a "hledat v širším okolí" ho posune
   * o krok nahoru, aniž by trvale přepsal, co si uživatel nastavil.
   */
  var activeRadius by mutableStateOf(nearbyRadiusStore.radius)
    private set

  /** Zapamatovaný obchod z minule — předvybere se jen podle [storeToPreselect]. */
  var rememberedStore by mutableStateOf<Store?>(null)
    private set

  // Poloha uživatele žije JEN v paměti ViewModelu po dobu zobrazené obrazovky (docs/soukromi.md)
  // — potřebuje ji "širší okolí" i nabídka z OSM, aby se nemuselo znovu čekat na GPS fix, a
  // hlavně výpočet skutečné vzdálenosti, který na serveru udělat nejde (posílá se mu poloha
  // zaokrouhlená na ~110 m).
  private var lastLat: Double? = null
  private var lastLon: Double? = null

  // Automatické hledání smí proběhnout jen jednou za život ViewModelu. LaunchedEffect(Unit) na
  // to nestačí: navigation-compose obrazovku při odchodu zahodí a při návratu (z formuláře
  // obchodu/zboží) postaví znovu, zatímco ViewModel přežívá ve svém NavBackStackEntry.
  private var autoLocateDone = false

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

  /**
   * Zapamatovaný obchod se načte, ale NEVYBERE se rovnou jako dřív — o předvýběr se stará
   * [storeToPreselect] podle toho, jestli je obchod mezi nalezenými v okolí. Bez polohy
   * (nepovolená, vypnutá, offline) vyhraje zapamatovaný obchod stejně jako dřív.
   */
  private fun loadRememberedStore() {
    // Snapshot ze SharedPreferences je k dispozici hned a bez sítě — teprve díky tomu jde
    // zapsat cenu v obchodě bez signálu, kde `storeById` selže (docs/design.md, čtvrtý stav).
    lastStoreStore.remembered()?.let {
      rememberedStore = it
      applyPreselection()
    }
    val id = lastStoreStore.rememberedId() ?: return
    viewModelScope.launch {
      try {
        graphQlClient.storeById(id)?.let { store ->
          rememberedStore = store
          applyPreselection()
        } ?: lastStoreStore.clear()
      } catch (e: Exception) {
        // Výpadek načtení posledního obchodu nesmí blokovat ruční výběr — snapshot výš platí dál.
      }
    }
  }

  /**
   * Předvybere obchod, jakmile je znám jak zapamatovaný obchod, tak (ne)úspěch hledání v okolí
   * — obojí dorazí asynchronně a v libovolném pořadí. Ručně vybraný obchod se nikdy nepřepisuje.
   */
  private fun applyPreselection() {
    if (selectedStore != null) return
    val nearby = (nearbyState as? NearbyState.Loaded)?.stores ?: emptyList()
    val locationKnown = nearbyState is NearbyState.Loaded
    val candidate = storeToPreselect(rememberedStore, nearby, locationKnown) ?: return
    if (product != null && !productAvailableAtStore(product!!, candidate)) return
    selectStore(candidate)
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
        // Chyba našeptávače nesmí blokovat zápis — seznam z okolí a ruční výběr fungují dál.
      } finally {
        storeSearching = false
      }
    }
  }

  fun onStoreSelected(store: Store) {
    selectStore(store)
    // Nekompatibilní obchod se rovnou neschová (na rozdíl od discardIncompatibleStore při
    // změně produktu) — uživatel ho právě vybral ručně, appka jen zablokuje odeslání a napíše
    // proč, ať mu výběr nezmizí beze stopy.
    storeScopeMismatch = product?.let { !productAvailableAtStore(it, store) } ?: false
    if (!storeScopeMismatch) lastStoreStore.remember(store)
  }

  private fun selectStore(store: Store) {
    selectedStore = store
    storeQuery = storeLabel(store, countryStore.country)
  }

  /** Návrat z formuláře nového obchodu (StoreFormScreen) — rovnou ho vybrat. */
  fun onNewStoreCreated(store: Store) {
    onStoreSelected(store)
    storeSuggestions = emptyList()
    // Nabídka z OSM je po založení zastaralá — obsahuje i obchod, který právě vznikl, a jeho
    // dalším klepnutím by uživatel mířil na duplicitu. Načte se znovu, až si o ni řekne.
    osmState = OsmState.Idle
  }

  /**
   * Smí teď proběhnout automatické hledání v okolí? Vrací true nejvýš jednou — viz
   * [autoLocateDone]. Obrazovka se ptá až při rozbalení sekce "Zadat cenu": pro nového
   * uživatele je sekce schovaná (PriceEntryVisibilityStore), takže by GPS fix i dotaz na server
   * shořely naprázdno.
   */
  fun shouldAutoLocate(): Boolean {
    if (autoLocateDone) return false
    autoLocateDone = true
    return true
  }

  fun startLocating() {
    nearbyState = NearbyState.Locating
  }

  fun onLocationPermissionMissing() {
    nearbyState = NearbyState.PermissionMissing
    applyPreselection()
  }

  fun onLocationDisabled() {
    nearbyState = NearbyState.LocationDisabled
    applyPreselection()
  }

  fun onLocationTimeout() {
    nearbyState = NearbyState.LocationTimeout
    applyPreselection()
  }

  fun onLocationResolved(lat: Double, lon: Double) {
    lastLat = lat
    lastLon = lon
    loadNearby(lat, lon)
  }

  /** "Hledat v širším okolí" — jeden krok po škále z Nastavení, bez čekání na nový GPS fix. */
  fun widenRadius() {
    val wider = widerNearbyRadius(activeRadius) ?: return
    activeRadius = wider
    val lat = lastLat
    val lon = lastLon
    if (lat != null && lon != null) loadNearby(lat, lon)
  }

  private fun loadNearby(lat: Double, lon: Double) {
    nearbyState = NearbyState.Locating
    osmState = OsmState.Idle
    viewModelScope.launch {
      try {
        val radiusMeters = activeRadius.meters
        // Server hledá s okruhem zvětšeným o chybu zaokrouhlení polohy, takže dofiltrovat
        // a seřadit podle SKUTEČNÉ vzdálenosti umí až klient — jen on zná přesnou polohu.
        val stores = nearbyStoresWithin(
          graphQlClient.nearbyStores(lat, lon, activeRadius.radiusKm),
          lat, lon, radiusMeters,
        )
        nearbyState = NearbyState.Loaded(stores)
        // Nabídka jde do stejného seznamu jako našeptávač, aby měl StorePicker jeden zdroj
        // pravdy i pro mapu; psaní do pole ji přepíše, což je správně.
        storeSuggestions = stores.map { it.store }
        applyPreselection()
      } catch (e: Exception) {
        nearbyState = NearbyState.Failed(e.toUiText())
        applyPreselection()
      }
    }
  }

  /** Atribuce k aktuální nabídce z OSM — formulář nového obchodu ji musí zobrazit (ODbL). */
  fun osmAttribution(): String? = (osmState as? OsmState.Loaded)?.attribution

  /**
   * Nabídka provozoven z OpenStreetMap. Volá se líně — teprve když vlastní katalog v okolí nic
   * nemá, nebo když si o ni uživatel řekne: Overpass je pomalý a jeho kvóta je cizí.
   */
  fun loadOsmCandidates() {
    val lat = lastLat ?: return
    val lon = lastLon ?: return
    if (osmState is OsmState.Loading) return
    osmState = OsmState.Loading
    viewModelScope.launch {
      try {
        val result = graphQlClient.nearbyOsmStores(lat, lon, activeRadius.radiusKm)
        osmState = if (result.status != "OK") {
          OsmState.Unavailable
        } else {
          val known = (nearbyState as? NearbyState.Loaded)?.stores?.map { it.store } ?: emptyList()
          OsmState.Loaded(
            osmCandidatesNotInOwnCatalog(
              nearbyOsmStoresWithin(result.candidates, lat, lon, activeRadius.meters),
              known,
            ),
            result.attribution,
          )
        }
      } catch (e: Exception) {
        osmState = OsmState.Failed(e.toUiText())
      }
    }
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
