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

const val DEFAULT_PAGE_SIZE = 10
val PAGE_SIZE_OPTIONS = listOf(10, 20, 50, 100)

/**
 * Stav jedné sekce výpisu (zboží/obchody/ceny/úpravy) — čtyři instance, jedna na záložku.
 * Skutečné stránkování (ne "načíst další"), aby appka při hodně položkách nikdy netáhla celý
 * seznam — velikost stránky je uživatelova volba (`PaginationBar`, `MyContributionsScreen.kt`).
 */
class ContributionSection<T> {
  var items: List<T> by mutableStateOf(emptyList())
    private set
  var totalCount: Int by mutableStateOf(0)
    private set
  var pageIndex: Int by mutableStateOf(1)
    private set
  var pageSize: Int by mutableStateOf(DEFAULT_PAGE_SIZE)
    private set
  var loading: Boolean by mutableStateOf(false)
    private set
  var error: UiText? by mutableStateOf(null)
    private set

  fun startLoading() {
    loading = true
    error = null
  }

  fun applyResult(newItems: List<T>, newTotalCount: Int) {
    items = newItems
    totalCount = newTotalCount
    loading = false
  }

  fun applyError(message: UiText) {
    error = message
    loading = false
  }

  fun goToPage(index: Int) {
    pageIndex = index
  }

  /** Změna velikosti stránky vždy skočí zpátky na první stránku. */
  fun changePageSize(size: Int) {
    pageSize = size
    pageIndex = 1
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
    loadProducts()
    loadStores()
    loadObservations()
    loadEdits()
  }

  fun loadProducts() {
    val offset = (products.pageIndex - 1) * products.pageSize
    products.startLoading()
    viewModelScope.launch {
      try {
        val result = graphQlClient.myProducts(products.pageSize, offset)
        products.applyResult(result.items, result.totalCount)
      } catch (e: Exception) {
        products.applyError(e.toUiText())
      }
    }
  }

  fun changeProductsPage(index: Int) {
    products.goToPage(index)
    loadProducts()
  }

  fun changeProductsPageSize(size: Int) {
    products.changePageSize(size)
    loadProducts()
  }

  fun loadStores() {
    val offset = (stores.pageIndex - 1) * stores.pageSize
    stores.startLoading()
    viewModelScope.launch {
      try {
        val result = graphQlClient.myStores(stores.pageSize, offset)
        stores.applyResult(result.items, result.totalCount)
      } catch (e: Exception) {
        stores.applyError(e.toUiText())
      }
    }
  }

  fun changeStoresPage(index: Int) {
    stores.goToPage(index)
    loadStores()
  }

  fun changeStoresPageSize(size: Int) {
    stores.changePageSize(size)
    loadStores()
  }

  fun loadObservations() {
    val offset = (observations.pageIndex - 1) * observations.pageSize
    observations.startLoading()
    viewModelScope.launch {
      try {
        val result = graphQlClient.myObservations(observations.pageSize, offset)
        observations.applyResult(result.items, result.totalCount)
      } catch (e: Exception) {
        observations.applyError(e.toUiText())
      }
    }
  }

  fun changeObservationsPage(index: Int) {
    observations.goToPage(index)
    loadObservations()
  }

  fun changeObservationsPageSize(size: Int) {
    observations.changePageSize(size)
    loadObservations()
  }

  fun loadEdits() {
    val offset = (edits.pageIndex - 1) * edits.pageSize
    edits.startLoading()
    viewModelScope.launch {
      try {
        val result = graphQlClient.myEdits(edits.pageSize, offset)
        edits.applyResult(result.items, result.totalCount)
      } catch (e: Exception) {
        edits.applyError(e.toUiText())
      }
    }
  }

  fun changeEditsPage(index: Int) {
    edits.goToPage(index)
    loadEdits()
  }

  fun changeEditsPageSize(size: Int) {
    edits.changePageSize(size)
    loadEdits()
  }
}
