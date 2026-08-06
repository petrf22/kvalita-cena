package cz.kvalitacena.ui.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.network.CreateStoreInput
import cz.kvalitacena.network.GeocodeCandidate
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.Store
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SIMILAR_CHECK_DEBOUNCE_MS = 400L

/**
 * Založení provozovny mimo skenování/GPS — pro zápis ceny bez sdílení polohy nebo zpětně
 * z domova (docs/datovy-model.md, "Identita provozovny"). Vyžaduje přihlášení (backend
 * StoreService). Tahle obrazovka sama přihlášení nekontroluje — gating je na vstupním bodě
 * (StorePicker "+ Přidat nový obchod" se anonymovi vůbec nenabídne, viz StorePicker.kt), stejně
 * jako na webu (frontend shared/store-picker.ts). Kdyby appka sem přesto pustila anonyma,
 * GraphQL vrátí UNAUTHORIZED a chyba se prostě zobrazí, stejně jako u rateProduct.
 */
class StoreFormViewModel(private val graphQlClient: GraphQlClient) : ViewModel() {

  var name by mutableStateOf("")
  var street by mutableStateOf("")
  var city by mutableStateOf("")
  var postalCode by mutableStateOf("")
  var ico by mutableStateOf("")

  var icoLookupLoading by mutableStateOf(false)
    private set
  var icoLookupError by mutableStateOf<String?>(null)
    private set

  // "Našli jsme podobné" — povinný krok před uložením (docs/datovy-model.md), server má
  // navíc tvrdou pojistku (uq_store_identity), tohle je jen včasné varování uživateli.
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

  var saving by mutableStateOf(false)
    private set
  var saveError by mutableStateOf<String?>(null)
    private set
  var created by mutableStateOf<Store?>(null)
    private set

  fun onNameChange(value: String) {
    name = value
    scheduleSimilarCheck()
  }

  fun onCityChange(value: String) {
    city = value
    scheduleSimilarCheck()
  }

  private fun scheduleSimilarCheck() {
    similarCheckJob?.cancel()
    if (name.isBlank() || city.isBlank()) {
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
    if (!isIcoShapeValid(trimmed) || trimmed.isBlank()) {
      icoLookupError = "IČO musí mít 8 číslic."
      return
    }
    icoLookupLoading = true
    icoLookupError = null
    viewModelScope.launch {
      try {
        val company = graphQlClient.companyByIco(trimmed)
        if (company == null) {
          icoLookupError = "V ARES jsme tohle IČO nenašli."
        } else {
          if (name.isBlank()) name = company.name
          if (street.isBlank()) company.street?.let { street = it }
          if (city.isBlank()) company.city?.let { city = it }
          if (postalCode.isBlank()) company.postalCode?.let { postalCode = it }
        }
      } catch (e: Exception) {
        icoLookupError = "Dotaz do ARES se nepovedl, zkus to prosím znovu."
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

  fun useMyLocation(lat: Double, lon: Double) {
    manualLat = lat
    manualLon = lon
    selectedCandidate = null
  }

  fun submit() {
    if (!isStoreFormValid(name, city) || !isIcoShapeValid(ico)) return
    saving = true
    saveError = null
    viewModelScope.launch {
      try {
        val input = CreateStoreInput(
          name = name.trim(),
          street = street.trim().ifBlank { null },
          city = city.trim(),
          postalCode = postalCode.trim().ifBlank { null },
          ico = ico.trim().ifBlank { null },
          lat = selectedCandidate?.lat ?: manualLat,
          lon = selectedCandidate?.lon ?: manualLon,
          geoSource = if (selectedCandidate != null) "OSM" else if (manualLat != null) "COMMUNITY" else null,
          osmRef = selectedCandidate?.osmRef,
        )
        created = graphQlClient.createStore(input)
      } catch (e: Exception) {
        saveError = e.message ?: "Založení obchodu se nepovedlo, zkus to prosím znovu."
      } finally {
        saving = false
      }
    }
  }
}
