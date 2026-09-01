package cz.kvalitacena.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.R
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.Photo
import cz.kvalitacena.network.PriceHistory
import cz.kvalitacena.network.Product
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.common.toUiText
import kotlinx.coroutines.launch

/** Rozsahy grafu nabízené v UI — dny odpovídají přímo argumentu `days` v priceHistory. */
val CHART_RANGES = listOf(
  7 to R.string.chart_range_7d,
  30 to R.string.chart_range_30d,
  90 to R.string.chart_range_90d,
  365 to R.string.chart_range_365d,
)

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

  var ratingError by mutableStateOf<UiText?>(null)
    private set

  var flagging by mutableStateOf(false)
    private set
  var flagMessage by mutableStateOf<UiText?>(null)
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

  fun rate(stars: Int) {
    ratingError = null
    viewModelScope.launch {
      try {
        val quality = graphQlClient.rateProduct(productId, stars)
        product = product?.copy(quality = quality, myQualityRating = stars)
      } catch (e: Exception) {
        ratingError = e.toUiText()
      }
    }
  }

  /** Po uploadu/smazání/přeřazení fotky (PhotoGallery/PhotoPicker) — jen lokální stav, appka fotku nenačítá znovu z produktu. */
  fun onPhotosChange(photos: List<Photo>) {
    product = product?.copy(photos = photos)
  }

  /** Po návratu z editace (ProductFormScreen productId != null) — updateProduct vrací celý
   *  PRODUCT_DETAIL_FIELDS (na rozdíl od updateStore), takže stačí prosté nahrazení stavu. */
  fun onProductUpdated(updated: Product) {
    product = updated
  }

  /** Hlasuje se o FAKTU, nikdy o ČLOVĚKU (docs/reputace.md, "Nesouhlas se vyjadřuje k faktu"). */
  fun flagProduct() {
    flagging = true
    flagMessage = null
    viewModelScope.launch {
      try {
        val result = graphQlClient.flagRecord("PRODUCT", productId)
        flagMessage = UiText.Res(
          if (result.hidden) R.string.product_report_hidden else R.string.report_acknowledged,
        )
      } catch (e: Exception) {
        flagMessage = e.toUiText()
      } finally {
        flagging = false
      }
    }
  }
}
