package cz.kvalitacena.ui.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.R
import cz.kvalitacena.network.CreateStoreInput
import cz.kvalitacena.network.GeocodeCandidate
import cz.kvalitacena.network.GraphQlAppException
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.OsmStoreCandidate
import cz.kvalitacena.network.RetailChain
import cz.kvalitacena.network.Store
import cz.kvalitacena.network.UpdateStoreInput
import cz.kvalitacena.ui.common.KNOWN_COUNTRIES
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.common.companyIdDigits
import cz.kvalitacena.ui.common.companyIdLabelRes
import cz.kvalitacena.ui.common.toUiText
import cz.kvalitacena.ui.settings.CountryStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SIMILAR_CHECK_DEBOUNCE_MS = 400L
private const val CHAIN_SEARCH_DEBOUNCE_MS = 300L

// Delší než kontrola podobných obchodů — geokódování jde na rate-limitovanou třetí stranu
// (Nominatim, nejvýš 1 dotaz/s napříč celou appkou), takže se nesmí spouštět po každém písmenu.
private const val GEOCODE_DEBOUNCE_MS = 800L

/**
 * Založení provozovny mimo skenování/GPS — pro zápis ceny bez sdílení polohy nebo zpětně
 * z domova (docs/datovy-model.md, "Identita provozovny"). Vyžaduje přihlášení (backend
 * StoreService). Tahle obrazovka sama přihlášení nekontroluje — gating je na vstupním bodě
 * (StorePicker "+ Přidat nový obchod" se anonymovi vůbec nenabídne, viz StorePicker.kt), stejně
 * jako na webu (frontend shared/store-picker.ts). Kdyby appka sem přesto pustila anonyma,
 * GraphQL vrátí UNAUTHORIZED a chyba se prostě zobrazí, stejně jako u rateProduct.
 *
 * Se zadaným [editingStoreId] přejde do režimu editace existující provozovny (patch nad
 * core.store_user_edit, `updateStore`) — používá ji StoreDetailScreen. Webový protějšek:
 * frontend shared/store-form.ts.
 */
class StoreFormViewModel(
  private val graphQlClient: GraphQlClient,
  private val editingStoreId: String? = null,
  private val countryStore: CountryStore,
) : ViewModel() {

  val isEditing: Boolean get() = editingStoreId != null

  var name by mutableStateOf("")

  /**
   * Číselník řetězců pro našeptávání (docs/stav-implementace.md). Výběr předvyplní [name], ale
   * JEN pokud je pole názvu ještě prázdné — rozepsaný název se nikdy nepřepíše.
   */
  var chainQuery by mutableStateOf("")
    private set
  var chainId by mutableStateOf<String?>(null)
    private set
  var chainSuggestions by mutableStateOf<List<RetailChain>>(emptyList())
    private set
  var chainSearching by mutableStateOf(false)
    private set
  private var chainSearchJob: Job? = null

  var street by mutableStateOf("")
  var city by mutableStateOf("")
  var postalCode by mutableStateOf("")
  var ico by mutableStateOf("")
  var url by mutableStateOf("")

  /**
   * Určuje popisek/tvar IČO-NIP a viditelnost "Načíst z ARES" (docs/lokalizace.md). Uživatel ji
   * teď může i ručně přepsat ve formuláři (dřív šla jen natvrdo 'CZ', přepsatelná jen skrz
   * reverseGeocode "Použít mou polohu" — slovenský obchod založený z domova se tak ukládal jako
   * český a dostal CZK navěky, viz docs/lokalizace.md "Country selector v UI"). V režimu editace
   * jde ze store.country, při zakládání z [CountryStore] (viewerova volba v Nastavení).
   */
  var country by mutableStateOf(countryStore.country)
    private set

  /** Vědomá volba země se nesmí přepsat reverzním geokódováním — u pohraničí mění i měnu zápisu. */
  private var countryTouched = false

  fun onCountryChange(code: String) {
    countryTouched = true
    country = code
    scheduleGeocode()
  }

  var loadingExisting by mutableStateOf(editingStoreId != null)
    private set

  var icoLookupLoading by mutableStateOf(false)
    private set
  var icoLookupError by mutableStateOf<UiText?>(null)
    private set

  // "Našli jsme podobné" — povinný krok před uložením (docs/datovy-model.md), server má
  // navíc tvrdou pojistku (uq_store_identity), tohle je jen včasné varování uživateli.
  // V režimu editace nedává smysl (obchod už existuje), viz onNameChange/onCityChange.
  var similarStores by mutableStateOf<List<Store>>(emptyList())
    private set
  private var similarCheckJob: Job? = null

  var showMap by mutableStateOf(false)
    private set

  var geocodeCandidates by mutableStateOf<List<GeocodeCandidate>>(emptyList())
    private set
  var geocodeAttribution by mutableStateOf<String?>(null)
    private set
  // Dva samostatné joby: fillAddress ruší jen doplňování adresy, ne běžící geokódování, ze
  // kterého ho jednoznačný kandidát volá (jinak by korutina rušila sama sebe).
  private var geocodeJob: Job? = null
  private var locationJob: Job? = null
  private var locationRequest = 0
  var locationMessage by mutableStateOf<UiText?>(null)
    private set
  var geocoding by mutableStateOf(false)
    private set
  var selectedCandidate by mutableStateOf<GeocodeCandidate?>(null)
    private set

  /**
   * Provozovna převzatá z nabídky OpenStreetMap — na rozdíl od [selectedCandidate]
   * (geokódovaná adresa, tedy pouhý BOD) odtud pochází i název a adresa, takže se obchod uloží
   * s `dataOrigin = OSM_DERIVED` (docs/datovy-model.md, pravidla 1 a 2).
   */
  var osmCandidate by mutableStateOf<OsmStoreCandidate?>(null)
    private set
  var osmAttribution by mutableStateOf<String?>(null)
    private set

  var manualLat by mutableStateOf<Double?>(null)
    private set
  var manualLon by mutableStateOf<Double?>(null)
    private set
  var locating by mutableStateOf(false)
    private set

  var saving by mutableStateOf(false)
    private set
  var saveError by mutableStateOf<UiText?>(null)
    private set
  var created by mutableStateOf<Store?>(null)
    private set

  /**
   * Id obchodu, se kterým se ten zakládaný srazil (`DUPLICATE_STORE`,
   * `extensions.existingId`) — formulář z toho udělá nabídku "tenhle obchod už známe, použij
   * ho" místo chybové hlášky, která by byla slepá ulička.
   */
  var duplicateStoreId by mutableStateOf<String?>(null)
    private set
  var loadingDuplicate by mutableStateOf(false)
    private set

  init {
    if (editingStoreId != null) loadExisting(editingStoreId)
  }

  private fun loadExisting(id: String) {
    viewModelScope.launch {
      try {
        graphQlClient.storeById(id)?.let { store ->
          name = store.name
          chainId = store.chain?.id
          chainQuery = store.chain?.name.orEmpty()
          street = store.street.orEmpty()
          city = store.city
          postalCode = store.postalCode.orEmpty()
          ico = store.ico.orEmpty()
          url = store.url.orEmpty()
          country = store.country
          // Store (GraphQL) nevrací osmRef zvoleného kandidáta (jen core.store.osm_ref
          // interně), takže se u editace nedá obnovit "vybraný kandidát" — jen souřadnice
          // samotné. Dokud se souřadnice na mapě nezmění, uloží se zpátky jako COMMUNITY
          // (viz submit()) — menší nepřesnost v provenienci, ne v samotné poloze.
          manualLat = store.lat
          manualLon = store.lon
        }
      } catch (e: Exception) {
        saveError = e.toUiText()
      } finally {
        loadingExisting = false
      }
    }
  }

  /**
   * Předvyplnění z kandidáta vybraného v nabídce OpenStreetMap. Uživatel může cokoli přepsat —
   * značka původu zůstává, protože jde o to, ODKUD data pocházejí, ne jestli je pak ještě někdo
   * upravil.
   */
  fun applyOsmCandidate(candidate: OsmStoreCandidate, attribution: String?) {
    if (osmCandidate?.osmRef == candidate.osmRef) return
    osmCandidate = candidate
    osmAttribution = attribution
    name = candidate.name
    street = candidate.street.orEmpty()
    city = candidate.city.orEmpty()
    postalCode = candidate.postalCode.orEmpty()
    // Appka umí jen CZ/SK/PL; addr:country v OSM navíc často chybí. Neznámá země se ignoruje
    // a zůstane viewerova volba z Nastavení (docs/lokalizace.md).
    if (candidate.country in KNOWN_COUNTRIES) country = candidate.country!!
    manualLat = candidate.lat
    manualLon = candidate.lon
    showMap = true
    selectedCandidate = null
    geocodeCandidates = emptyList()
    prefillChainFromBrand(candidate.brand)
    scheduleSimilarCheck()
  }

  /**
   * Značka z OSM se do pole řetězce dostane, JEN když jí odpovídá položka vlastního číselníku
   * (`core.retail_chain` je kurátorský, nový řetězec z OSM nevzniká). U nezávislého obchodu
   * bývá `brand` totéž co název — předvyplnit ho natvrdo by znamenalo ukázat vyplněný řetězec,
   * který na nic neodkazuje.
   */
  private fun prefillChainFromBrand(brand: String?) {
    val query = brand?.trim()?.takeIf { it.isNotBlank() } ?: return
    viewModelScope.launch {
      try {
        val match = graphQlClient.chains(query = query, country = country)
          .firstOrNull { it.name.equals(query, ignoreCase = true) } ?: return@launch
        onChainSelect(match)
      } catch (e: Exception) {
        // Našeptávač je jen doporučující — chyba dotazu nesmí blokovat založení obchodu.
      }
    }
  }

  fun onNameChange(value: String) {
    name = value
    scheduleSimilarCheck()
  }

  fun onStreetChange(value: String) {
    street = value
    scheduleGeocode()
  }

  fun onPostalCodeChange(value: String) {
    postalCode = value
    scheduleGeocode()
  }

  fun onChainQueryChange(value: String) {
    chainQuery = value
    // Smazání textu zruší i vazbu na řetězec — dokud uživatel nevybere jinou položku z nabídky,
    // psaní nad vybraným řetězcem ho jen přepisuje, ne mění (stejný princip jako web nzAllowClear).
    if (value.isBlank()) chainId = null
    chainSearchJob?.cancel()
    chainSearchJob = viewModelScope.launch {
      delay(CHAIN_SEARCH_DEBOUNCE_MS)
      chainSearching = true
      try {
        chainSuggestions = graphQlClient.chains(query = value.trim().ifBlank { null }, country = country)
      } catch (e: Exception) {
        // Našeptávač je jen doporučující — chyba dotazu nesmí blokovat založení obchodu.
      } finally {
        chainSearching = false
      }
    }
  }

  fun onChainSelect(chain: RetailChain) {
    chainId = chain.id
    chainQuery = chain.name
    // Předvyplní název JEN pokud je pole ještě prázdné — rozepsaný název výběr nikdy nepřepíše.
    if (name.isBlank()) {
      onNameChange(chain.name)
    }
  }

  fun onCityChange(value: String) {
    city = value
    scheduleSimilarCheck()
    scheduleGeocode()
  }

  /**
   * Souřadnice se dohledají SAMY z vyplněné adresy — dřív na to bylo tlačítko "Najít
   * souřadnice", které uživatel nenašel, takže obchody vznikaly bez polohy a v okolí je pak
   * nikdo nenajde (`StoreRepository.findNearby` filtruje `lat IS NOT NULL`).
   *
   * Nikdy nepřepíše bod, který už obchod má — ať z mapy, z "Použít mou polohu", nebo z OSM
   * kandidáta. Stejný princip "doplň jen prázdné" jako u [useMyLocation] a [lookupIco].
   */
  private fun scheduleGeocode() {
    geocodeJob?.cancel()
    if (city.isBlank() || hasCoordinates()) return
    geocodeJob = viewModelScope.launch {
      delay(GEOCODE_DEBOUNCE_MS)
      geocode(silent = true)
    }
  }

  private fun hasCoordinates(): Boolean =
    selectedCandidate != null || osmCandidate != null || (manualLat != null && manualLon != null)

  private fun scheduleSimilarCheck() {
    similarCheckJob?.cancel()
    if (isEditing || name.isBlank() || city.isBlank()) {
      similarStores = emptyList()
      return
    }
    similarCheckJob = viewModelScope.launch {
      delay(SIMILAR_CHECK_DEBOUNCE_MS)
      try {
        similarStores = graphQlClient.searchStores(query = name, city = city, first = 5).items
      } catch (e: Exception) {
        // Kontrola podobných je jen doporučující — chyba dotazu nesmí blokovat založení.
      }
    }
  }

  /** [onFound] se volá jen při reálném nálezu — pro odlišení "formulář se skutečně změnil" od
   *  neúspěšného pokusu, viz `formDirty` ve `StoreFormScreen`. */
  fun lookupIco(onFound: () -> Unit = {}) {
    val trimmed = ico.trim()
    if (!isIcoShapeValid(trimmed, country) || trimmed.isBlank()) {
      val digits = companyIdDigits(country)
      icoLookupError = UiText.Res(
        R.string.store_company_id_shape_invalid,
        listOf(UiText.Res(companyIdLabelRes(country)), digits ?: 0),
      )
      return
    }
    icoLookupLoading = true
    icoLookupError = null
    viewModelScope.launch {
      try {
        val company = graphQlClient.companyByIco(trimmed)
        if (company == null) {
          icoLookupError = UiText.Res(R.string.store_company_id_not_found_in_registry)
        } else {
          onFound()
          if (name.isBlank()) name = company.name
          if (street.isBlank()) company.street?.let { street = it }
          if (city.isBlank()) company.city?.let { city = it }
          if (postalCode.isBlank()) company.postalCode?.let { postalCode = it }
        }
      } catch (e: Exception) {
        icoLookupError = e.toUiText()
      } finally {
        icoLookupLoading = false
      }
    }
  }

  /**
   * Ruční zopakování — automaticky to dělá [scheduleGeocode]. Tlačítko zůstává kvůli tomu, že
   * dohledání může tiše selhat (Nominatim blokuje datacentrové IP, docs/spusteni.md), a chybový
   * stav musí jít zopakovat (docs/design.md).
   */
  fun geocode(silent: Boolean = false) {
    if (city.isBlank()) return
    val request = ++locationRequest
    val address = listOf(street, city, postalCode, country)
    geocoding = true
    geocodeJob?.cancel()
    locating = false
    locationMessage = null
    geocodeJob = viewModelScope.launch {
      try {
        val result = graphQlClient.geocodeAddress(street.trim().ifBlank { null }, city.trim(), postalCode.trim().ifBlank { null }, country)
        if (request != locationRequest || address != listOf(street, city, postalCode, country)) return@launch
        geocodeAttribution = result.attribution
        if (result.candidates.size == 1) {
          // Jediný nález není co vybírat — formulář ho rovnou použije a jen to oznámí
          // (store_location_found_from_address ve StoreFormScreen).
          geocodeCandidates = emptyList()
          selectCandidate(result.candidates.single())
        } else if (result.candidates.isNotEmpty()) {
          geocodeCandidates = result.candidates
          // Nový seznam kandidátů znamená novou volbu — dosavadní bod se musí zahodit, jinak
          // by submit() uložil souřadnice i osmRef kandidáta k PŘEDCHOZÍ adrese. Prázdný
          // seznam a chyba bod naopak zachovají (docs/overeni-zadavani-obchodu.md).
          selectedCandidate = null
          manualLat = null
          manualLon = null
        } else {
          geocodeCandidates = emptyList()
          // Automatické dohledání při psaní adresy je u prázdného výsledku TICHÉ: obchod jde
          // uložit i bez souřadnic (docs/datovy-model.md), takže to není chyba.
          if (!silent) locationMessage = UiText.Res(R.string.store_location_not_found)
        }
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        if (request == locationRequest) {
          geocodeCandidates = emptyList()
          locationMessage = UiText.Res(R.string.store_location_not_found)
        }
      } finally {
        if (request == locationRequest) geocoding = false
      }
    }
  }

  fun selectCandidate(candidate: GeocodeCandidate) {
    showMap = true
    selectedCandidate = candidate
    manualLat = null
    manualLon = null
    fillAddress(candidate.lat, candidate.lon, false)
  }

  /** Klik/přetažení značky na mapě (LocationMap, editable) — ruční bod, ne kandidát z geokódování. */
  fun onMapPointSelected(lat: Double, lon: Double) {
    selectedCandidate = null
    manualLat = lat
    manualLon = lon
    fillAddress(lat, lon, true)
  }

  /**
   * Lístek se bere HNED, ne až v [fillAddress] — čekání na GPS trvá a uživatel mezitím může
   * stisknout "Najít souřadnice"; opožděná poloha by pak novější hledání přebila. Obrazovka
   * volá [beginLocating] před zjišťováním polohy a číslo předá zpět do [useMyLocation].
   */
  fun beginLocating(): Int {
    locating = true
    locationMessage = null
    return ++locationRequest
  }

  /** Poloha se nezjistila (odmítnuté oprávnění, prázdný fix) — spinner nesmí zůstat viset. */
  fun cancelLocating(request: Int) {
    if (request == locationRequest) locating = false
  }

  fun useMyLocation(lat: Double, lon: Double, request: Int) {
    if (request != locationRequest) return
    showMap = true
    // Syrová hodnota schválně: manualLat/Lon je souřadnice PROVOZOVNY (uloží se do
    // core.store), zaokrouhlení by ji degradovalo. Pro Nominatim zaokrouhluje server
    // (GeocodingService.reverseGeocode, docs/soukromi.md).
    manualLat = lat
    manualLon = lon
    selectedCandidate = null
    fillAddress(lat, lon, false)
  }

  private fun fillAddress(lat: Double, lon: Double, replace: Boolean) {
    val request = ++locationRequest
    locationJob?.cancel()
    val oldStreet = street
    val oldCity = city
    val oldPostalCode = postalCode
    locating = true
    geocoding = false
    locationMessage = null
    locationJob = viewModelScope.launch {
      try {
        val result = graphQlClient.reverseGeocode(lat, lon)
        if (request != locationRequest) return@launch
        // Pole, které uživatel mezitím přepsal, zůstává jeho. Při replace (klik do mapy =
        // celá adresa nového bodu) se chybějící část MAŽE — jinak by po ulici zbyl kus
        // předchozí adresy a vznikla by smíchaná, ale zdánlivě platná adresa.
        fun merged(current: String, old: String, incoming: String?): String = when {
          current != old -> current
          replace -> incoming.orEmpty()
          current.isBlank() -> incoming ?: current
          else -> current
        }
        street = merged(street, oldStreet, result.street)
        city = merged(city, oldCity, result.city)
        postalCode = merged(postalCode, oldPostalCode, result.postalCode)
        if (!isEditing && !countryTouched && result.country in KNOWN_COUNTRIES) country = result.country!!
        geocodeAttribution = result.attribution
        if (result.street.isNullOrBlank() && result.city.isNullOrBlank() && result.postalCode.isNullOrBlank())
          locationMessage = UiText.Res(R.string.store_location_not_found)
        scheduleSimilarCheck()
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        if (request == locationRequest) locationMessage = UiText.Res(R.string.store_location_not_found)
      } finally {
        if (request == locationRequest) locating = false
      }
    }
  }

  fun submit() {
    if (!isStoreFormValid(name, city) || !isIcoShapeValid(ico, country) || !isUrlShapeValid(url)) return
    saving = true
    saveError = null
    duplicateStoreId = null
    val lat = selectedCandidate?.lat ?: manualLat
    val lon = selectedCandidate?.lon ?: manualLon
    val geoSource = if (selectedCandidate != null || osmCandidate != null) "OSM" else if (lat != null) "COMMUNITY" else null
    val osmRef = selectedCandidate?.osmRef ?: osmCandidate?.osmRef
    // OSM_DERIVED jen u provozovny převzaté z nabídky — u pouhého geokódování adresy pochází
    // z OpenStreetMap bod, ne text, a značka původu se nedává (docs/datovy-model.md, pravidlo 1).
    val dataOrigin = if (osmCandidate != null) "OSM_DERIVED" else null

    viewModelScope.launch {
      try {
        created = if (isEditing) {
          val input = UpdateStoreInput(
            name = name.trim(),
            chainId = chainId,
            clearChain = chainId == null,
            street = street.trim().ifBlank { null },
            clearStreet = street.trim().isEmpty(),
            city = city.trim(),
            postalCode = postalCode.trim().ifBlank { null },
            clearPostalCode = postalCode.trim().isEmpty(),
            // country jde na rozdíl od zbytku patche rovnou do globální provozovny, gatováno
            // důvěrou autora (docs/lokalizace.md, "Country selector v UI") — server sám pozná
            // no-op podle rovnosti s aktuální hodnotou.
            country = country,
            ico = ico.trim().ifBlank { null },
            clearIco = ico.trim().isEmpty(),
            lat = lat,
            lon = lon,
            geoSource = geoSource,
            osmRef = osmRef,
            url = url.trim().ifBlank { null },
            clearUrl = url.trim().isEmpty(),
          )
          graphQlClient.updateStore(editingStoreId!!, input)
        } else {
          val input = CreateStoreInput(
            name = name.trim(),
            chainId = chainId,
            street = street.trim().ifBlank { null },
            city = city.trim(),
            postalCode = postalCode.trim().ifBlank { null },
            country = country,
            ico = ico.trim().ifBlank { null },
            lat = lat,
            lon = lon,
            geoSource = geoSource,
            osmRef = osmRef,
            dataOrigin = dataOrigin,
            url = url.trim().ifBlank { null },
          )
          graphQlClient.createStore(input)
        }
      } catch (e: Exception) {
        saveError = e.toUiText()
        // Sražený obchod v katalogu už je — nabídnout ho je užitečnější než chybová hláška,
        // po které uživatel neví, co dál (server ho posílá v extensions.existingId).
        duplicateStoreId = (e as? GraphQlAppException)?.takeIf { it.code == "DUPLICATE_STORE" }?.existingId
      } finally {
        saving = false
      }
    }
  }

  /** "Tenhle obchod už známe — použít ho": načte existující provozovnu a vrátí ji volajícímu. */
  fun useDuplicateStore() {
    val id = duplicateStoreId ?: return
    loadingDuplicate = true
    viewModelScope.launch {
      try {
        graphQlClient.storeById(id)?.let { existing ->
          saveError = null
          duplicateStoreId = null
          created = existing
        }
      } catch (e: Exception) {
        saveError = e.toUiText()
      } finally {
        loadingDuplicate = false
      }
    }
  }
}
