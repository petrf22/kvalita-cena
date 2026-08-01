package cz.kvalitacena.ui.price

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.Product
import cz.kvalitacena.network.Store
import cz.kvalitacena.network.SubmitObservationInput
import kotlinx.coroutines.launch

/** Obrazovky 2–4 flow "sken → cena → výběr provozovny → odeslání" (viz plán projektu). */
class PriceEntryViewModel(
  private val graphQlClient: GraphQlClient,
  private val barcode: String,
) : ViewModel() {

  var loading by mutableStateOf(true)
    private set
  var product by mutableStateOf<Product?>(null)
    private set
  var notFound by mutableStateOf(false)
    private set

  var nearbyStores by mutableStateOf<List<Store>>(emptyList())
    private set
  var locating by mutableStateOf(false)
    private set
  var locationError by mutableStateOf<String?>(null)
    private set

  var selectedStoreId by mutableStateOf<String?>(null)
  var priceAmount by mutableStateOf("")
  var priceKind by mutableStateOf("REGULAR")

  var submitting by mutableStateOf(false)
    private set
  var submitSuccess by mutableStateOf(false)
    private set
  var submitError by mutableStateOf<String?>(null)
    private set

  init {
    loadProduct()
  }

  private fun loadProduct() {
    loading = true
    viewModelScope.launch {
      try {
        val found = graphQlClient.productByCode(barcode)
        product = found
        notFound = found == null
      } catch (e: Exception) {
        notFound = true
      } finally {
        loading = false
      }
    }
  }

  fun onLocationResolved(lat: Double, lon: Double) {
    locating = true
    locationError = null
    viewModelScope.launch {
      try {
        val stores = graphQlClient.nearbyStores(lat, lon)
        nearbyStores = stores
        if (stores.isEmpty()) {
          locationError = "V okolí jsme nenašli žádný obchod."
        } else {
          selectedStoreId = stores.first().id
        }
      } catch (e: Exception) {
        locationError = "Nepodařilo se najít obchody v okolí."
      } finally {
        locating = false
      }
    }
  }

  fun onLocationUnavailable() {
    locating = false
    locationError = "Polohu se nepodařilo zjistit — vyber obchod, až budou data."
  }

  fun startLocating() {
    locating = true
    locationError = null
  }

  fun submit() {
    val currentProduct = product ?: return
    val storeId = selectedStoreId ?: return
    val amount = priceAmount.replace(',', '.').toDoubleOrNull() ?: return

    submitting = true
    submitError = null
    submitSuccess = false
    viewModelScope.launch {
      try {
        graphQlClient.submitObservation(
          SubmitObservationInput(
            productId = currentProduct.id,
            storeId = storeId,
            priceAmount = amount,
            priceKind = priceKind,
          ),
        )
        submitSuccess = true
        priceAmount = ""
        loadProduct() // obnoví agregované ceny po zápisu
      } catch (e: Exception) {
        submitError = "Zápis se nepovedl, zkus to prosím znovu."
      } finally {
        submitting = false
      }
    }
  }
}
