package cz.kvalitacena.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.PriceHistory
import cz.kvalitacena.network.Product
import kotlinx.coroutines.launch

/** Rozsahy grafu nabízené v UI — dny odpovídají přímo argumentu `days` v priceHistory. */
val CHART_RANGES = listOf(7 to "7 dní", 30 to "30 dní", 90 to "90 dní", 365 to "365 dní")

class ProductDetailViewModel(
  private val graphQlClient: GraphQlClient,
  private val productId: String,
) : ViewModel() {

  var loading by mutableStateOf(true)
    private set
  var product by mutableStateOf<Product?>(null)
    private set
  var notFound by mutableStateOf(false)
    private set

  var history by mutableStateOf<PriceHistory?>(null)
    private set
  var historyLoading by mutableStateOf(false)
    private set
  var selectedPriceKind by mutableStateOf("REGULAR")
    private set
  var selectedDays by mutableStateOf(90)
    private set

  var ratingError by mutableStateOf<String?>(null)
    private set

  var flagging by mutableStateOf(false)
    private set
  var flagMessage by mutableStateOf<String?>(null)
    private set

  init {
    loadProduct()
  }

  private fun loadProduct() {
    loading = true
    viewModelScope.launch {
      try {
        val found = graphQlClient.productById(productId)
        product = found
        notFound = found == null
        if (found != null) loadHistory()
      } catch (e: Exception) {
        notFound = true
      } finally {
        loading = false
      }
    }
  }

  private fun loadHistory() {
    historyLoading = true
    viewModelScope.launch {
      try {
        history = graphQlClient.priceHistory(productId, selectedPriceKind, storeId = null, days = selectedDays)
      } catch (e: Exception) {
        history = null
      } finally {
        historyLoading = false
      }
    }
  }

  fun onDaysChange(days: Int) {
    selectedDays = days
    loadHistory()
  }

  fun onPriceKindChange(kind: String) {
    selectedPriceKind = kind
    loadHistory()
  }

  fun rate(grade: Int) {
    ratingError = null
    viewModelScope.launch {
      try {
        val quality = graphQlClient.rateProduct(productId, grade)
        product = product?.copy(quality = quality, myQualityRating = grade)
      } catch (e: Exception) {
        ratingError = "Hodnocení kvality vyžaduje přihlášení — dokonči ho v záložce Účet."
      }
    }
  }

  /** Hlasuje se o FAKTU, nikdy o ČLOVĚKU (docs/reputace.md, "Nesouhlas se vyjadřuje k faktu"). */
  fun flagProduct() {
    flagging = true
    flagMessage = null
    viewModelScope.launch {
      try {
        val result = graphQlClient.flagRecord("PRODUCT", productId)
        flagMessage = if (result.hidden) {
          "Díky za nahlášení — položka je teď skrytá a čeká na přezkum."
        } else {
          "Díky za nahlášení, zaznamenali jsme ho."
        }
      } catch (e: Exception) {
        flagMessage = "Nahlášení se nepovedlo, zkus to prosím znovu."
      } finally {
        flagging = false
      }
    }
  }
}
