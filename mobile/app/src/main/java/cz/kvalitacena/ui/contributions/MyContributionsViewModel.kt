package cz.kvalitacena.ui.contributions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.MyEditItem
import cz.kvalitacena.network.MyObservationItem
import cz.kvalitacena.network.MyProductItem
import cz.kvalitacena.network.MyStoreItem
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.common.toUiText
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 20

/** Stav jedné sekce výpisu (zboží/obchody/ceny/úpravy) — čtyři instance, jedna na záložku. */
class ContributionSection<T> {
  var items: List<T> by mutableStateOf(emptyList())
    private set
  var totalCount: Int by mutableStateOf(0)
    private set
  var hasMore: Boolean by mutableStateOf(false)
    private set
  var loading: Boolean by mutableStateOf(false)
    private set
  var error: UiText? by mutableStateOf(null)
    private set

  fun startLoading() {
    loading = true
    error = null
  }

  fun applyResult(reset: Boolean, newItems: List<T>, newTotalCount: Int, newHasMore: Boolean) {
    items = if (reset) newItems else items + newItems
    totalCount = newTotalCount
    hasMore = newHasMore
    loading = false
  }

  fun applyError(message: UiText) {
    error = message
    loading = false
  }
}

/**
 * "Moje příspěvky" (docs/datovy-model.md, "Uživatelská vrstva nad globálními daty"; prahy
 * v docs/reputace.md). Webový protějšek: frontend features/my-contributions/my-contributions-page.ts.
 */
class MyContributionsViewModel(private val graphQlClient: GraphQlClient) : ViewModel() {

  val products = ContributionSection<MyProductItem>()
  val stores = ContributionSection<MyStoreItem>()
  val observations = ContributionSection<MyObservationItem>()
  val edits = ContributionSection<MyEditItem>()

  init {
    loadProducts(reset = true)
    loadStores(reset = true)
    loadObservations(reset = true)
    loadEdits(reset = true)
  }

  fun loadProducts(reset: Boolean) {
    val offset = if (reset) 0 else products.items.size
    products.startLoading()
    viewModelScope.launch {
      try {
        val result = graphQlClient.myProducts(PAGE_SIZE, offset)
        products.applyResult(reset, result.items, result.totalCount, result.hasMore)
      } catch (e: Exception) {
        products.applyError(e.toUiText())
      }
    }
  }

  fun loadStores(reset: Boolean) {
    val offset = if (reset) 0 else stores.items.size
    stores.startLoading()
    viewModelScope.launch {
      try {
        val result = graphQlClient.myStores(PAGE_SIZE, offset)
        stores.applyResult(reset, result.items, result.totalCount, result.hasMore)
      } catch (e: Exception) {
        stores.applyError(e.toUiText())
      }
    }
  }

  fun loadObservations(reset: Boolean) {
    val offset = if (reset) 0 else observations.items.size
    observations.startLoading()
    viewModelScope.launch {
      try {
        val result = graphQlClient.myObservations(PAGE_SIZE, offset)
        observations.applyResult(reset, result.items, result.totalCount, result.hasMore)
      } catch (e: Exception) {
        observations.applyError(e.toUiText())
      }
    }
  }

  fun loadEdits(reset: Boolean) {
    val offset = if (reset) 0 else edits.items.size
    edits.startLoading()
    viewModelScope.launch {
      try {
        val result = graphQlClient.myEdits(PAGE_SIZE, offset)
        edits.applyResult(reset, result.items, result.totalCount, result.hasMore)
      } catch (e: Exception) {
        edits.applyError(e.toUiText())
      }
    }
  }
}
