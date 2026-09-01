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

  fun lookupIco() {
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
    if (city.isBlank()) return
    geocoding = true
    manualLat = null
    manualLon = null
    selectedCandidate = null
    viewModelScope.launch {
      try {
        val result = graphQlClient.geocodeAddress(street.trim().ifBlank { null }, city.trim(), postalCode.trim().ifBlank { null })
        geocodeCandidates = result.candidates
        geocodeAttribution = result.attribution
      } catch (e: Exception) {
        geocodeCandidates = emptyList()
      } finally {
        geocoding = false
      }
    }
  }

  fun selectCandidate(candidate: GeocodeCandidate) {
    selectedCandidate = candidate
    manualLat = null
    manualLon = null
  }

  /** Klik/přetažení značky na mapě (LocationMap, editable) — ruční bod, ne kandidát z geokódování. */
  fun onMapPointSelected(lat: Double, lon: Double) {
    selectedCandidate = null
    manualLat = lat
    manualLon = lon
  }

  fun useMyLocation(lat: Double, lon: Double) {
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

  fun submit() {
    if (!isStoreFormValid(name, city) || !isIcoShapeValid(ico, country) || !isUrlShapeValid(url)) return
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
