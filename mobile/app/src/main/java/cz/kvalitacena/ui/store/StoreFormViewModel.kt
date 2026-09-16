package cz.kvalitacena.ui.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.R
import cz.kvalitacena.network.CreateStoreInput
import cz.kvalitacena.network.GeocodeCandidate
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.OsmStoreCandidate
import cz.kvalitacena.ui.common.NavigationResults
import kotlinx.coroutines.CancellationException
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
  var osmQuery by mutableStateOf("")
  var osmCandidates by mutableStateOf<List<OsmStoreCandidate>>(emptyList())
    private set
  var osmSearching by mutableStateOf(false)
    private set
  var osmSearched by mutableStateOf(false)
    private set
  var osmError by mutableStateOf<UiText?>(null)
    private set
  var osmAttribution by mutableStateOf<String?>(null)
    private set
  var manualFormVisible by mutableStateOf(isEditing)
    private set
  private var osmJob: Job? = null
  private var geocodeJob: Job? = null
  private var geocodeRevision = 0
  private var osmRevision = 0
  var geocodeAttempted by mutableStateOf(false)
    private set
  var geocodeMessage by mutableStateOf<UiText?>(null)
    private set


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

  var geocodeCandidates by mutableStateOf<List<GeocodeCandidate>>(emptyList())
    private set
  var geocodeAttribution by mutableStateOf<String?>(null)
    private set
  var geocoding by mutableStateOf(false)
    private set
  var selectedCandidate by mutableStateOf<GeocodeCandidate?>(null)
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

  init {
    if (editingStoreId != null) loadExisting(editingStoreId)
    else {
      osmQuery = NavigationResults.storeSearchQuery.orEmpty()
      NavigationResults.storeSearchQuery = null
    }
  }

  fun showManualForm() {
    manualFormVisible = true
  }

  fun onOsmQueryChange(value: String) {
    osmRevision++
    osmJob?.cancel()
    osmQuery = value
    osmSearching = false
    osmCandidates = emptyList()
    osmError = null
    osmSearched = false
  }

  fun searchOsm() {
    if (osmQuery.trim().length < 3 || osmSearching) return
    osmSearching = true
    osmError = null
    osmSearched = false
    osmCandidates = emptyList()
    val revision = ++osmRevision
    val query = osmQuery.trim()
    val searchCountry = country
    osmJob = viewModelScope.launch {
      try {
        val result = graphQlClient.searchOsmStores(query, searchCountry)
        osmAttribution = result.attribution
        osmCandidates = result.candidates
        osmSearched = true
        if (!result.available) osmError = UiText.Res(R.string.osm_unavailable)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        osmError = e.toUiText()
      } finally {
        if (revision == osmRevision) osmSearching = false
      }
    }
  }

  fun selectOsmStore(candidate: OsmStoreCandidate) {
    invalidateCoordinates()
    name = candidate.name
    street = candidate.street.orEmpty()
    city = candidate.city.orEmpty()
    postalCode = candidate.postalCode.orEmpty()
    candidate.country?.takeIf { it in KNOWN_COUNTRIES }?.let { country = it }
    chainId = null
    chainQuery = ""
    selectedCandidate = GeocodeCandidate(candidate.lat, candidate.lon, candidate.displayName, candidate.osmRef)
    geocodeAttribution = osmAttribution
    manualFormVisible = true
    scheduleSimilarCheck()
  }

  fun onStreetChange(value: String) {
    street = value
    invalidateCoordinates()
  }

  fun onPostalCodeChange(value: String) {
    postalCode = value
    invalidateCoordinates()
  }

  fun onCountryChange(value: String) {
    if (country == value) return
    country = value
    onOsmQueryChange(osmQuery)
    invalidateCoordinates()
  }

  private fun invalidateCoordinates() {
    geocodeRevision++
    geocodeJob?.cancel()
    geocoding = false
    geocodeAttempted = false
    geocodeMessage = null
    geocodeCandidates = emptyList()
    selectedCandidate = null
    manualLat = null
    manualLon = null
  }

  fun useExisting(store: Store) {
    created = store
  }

  fun startLocating() {
    locating = true
    geocodeMessage = null
  }

  fun onLocationUnavailable() {
    locating = false
    geocodeMessage = UiText.Res(R.string.nearby_without_location)
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

  fun onNameChange(value: String) {
    name = value
    scheduleSimilarCheck()
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
    invalidateCoordinates()
    scheduleSimilarCheck()
  }

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

  fun geocode() {
    if (city.isBlank() || geocoding) return
    geocoding = true
    geocodeMessage = null
    val revision = ++geocodeRevision
    val address = listOf(street, city, postalCode, country)
    geocodeJob = viewModelScope.launch {
      try {
        val result = graphQlClient.geocodeAddress(
          address[0].trim().ifBlank { null }, address[1].trim(), address[2].trim().ifBlank { null }, address[3],
        )
        geocodeCandidates = result.candidates
        geocodeAttribution = result.attribution
        geocodeAttempted = true
        // Výsledek ukážeme k potvrzení; ani jediný nález nemusí být správná pobočka.
        geocodeMessage = UiText.Res(
          if (result.candidates.isEmpty()) R.string.store_geocode_empty else R.string.store_geocode_choose,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        geocodeAttempted = true
        geocodeMessage = e.toUiText()
      } finally {
        if (revision == geocodeRevision) geocoding = false
      }
    }
  }

  fun selectCandidate(candidate: GeocodeCandidate) {
    selectedCandidate = candidate
    geocodeMessage = null
    manualLat = null
    manualLon = null
  }

  /** Klik/přetažení značky na mapě (LocationMap, editable) — ruční bod, ne kandidát z geokódování. */
  fun onMapPointSelected(lat: Double, lon: Double) {
    geocodeRevision++
    geocodeJob?.cancel()
    geocoding = false
    geocodeMessage = null
    selectedCandidate = null
    manualLat = lat
    manualLon = lon
  }

  fun useMyLocation(lat: Double, lon: Double) {
    invalidateCoordinates()
    // Syrová hodnota schválně: manualLat/Lon je souřadnice PROVOZOVNY (uloží se do
    // core.store), zaokrouhlení by ji degradovalo. Pro Nominatim zaokrouhluje server
    // (GeocodingService.reverseGeocode, docs/soukromi.md).
    manualLat = lat
    manualLon = lon
    selectedCandidate = null
    // Doplní jen PRÁZDNÁ adresní pole — nepřepisuje, co uživatel už vyplnil (docs/soukromi.md:
    // reverseGeocode jde stejně jako geocodeAddress výhradně ze serveru).
    locating = true
    viewModelScope.launch {
      try {
        val result = graphQlClient.reverseGeocode(lat, lon)
        if (street.isBlank()) result.street?.let { street = it }
        if (city.isBlank()) result.city?.let { city = it }
        if (postalCode.isBlank()) result.postalCode?.let { postalCode = it }
        // Jen při zakládání — editovaná provozovna svou zemi už má (docs/lokalizace.md).
        // Neznámá země (appka umí jen CZ/SK/PL) se ignoruje, zůstane výchozí CZ.
        if (!isEditing && result.country in KNOWN_COUNTRIES) {
          country = result.country!!
        }
      } catch (e: Exception) {
        // Fail-soft na backendu i tady — adresa prostě zůstane nedoplněná.
      } finally {
        locating = false
      }
    }
  }

  fun submit(withoutCoordinates: Boolean = false) {
    if (saving || geocoding) return
    if (!isStoreFormValid(name, city) || !isIcoShapeValid(ico, country) || !isUrlShapeValid(url)) return
    // Adresu dohledáme i bez návštěvy sekce mapy; nejednoznačný bod musí potvrdit uživatel.
    if (selectedCandidate == null && manualLat == null && !withoutCoordinates && !isEditing) {
      geocode()
      return
    }
    saving = true
    saveError = null
    val lat = selectedCandidate?.lat ?: manualLat
    val lon = selectedCandidate?.lon ?: manualLon
    val geoSource = if (selectedCandidate != null) "OSM" else if (lat != null) "COMMUNITY" else null
    val osmRef = selectedCandidate?.osmRef

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
            url = url.trim().ifBlank { null },
          )
          graphQlClient.createStore(input)
        }
      } catch (e: Exception) {
        saveError = e.toUiText()
      } finally {
        saving = false
      }
    }
  }
}
