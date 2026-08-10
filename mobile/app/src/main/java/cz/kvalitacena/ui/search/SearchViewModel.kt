package cz.kvalitacena.ui.search

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.R
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.ProductSearchItem
import cz.kvalitacena.network.SearchFacets
import cz.kvalitacena.ui.common.UiText
import kotlinx.coroutines.launch

/**
 * Hledatelná pole řazení — hodnoty musí odpovídat GraphQL enumu ProductSort. Popisek jako
 * `@StringRes`, ne hotový text (docs/lokalizace.md) — `stringResource` je `@Composable`,
 * appka ho tahá až v `SearchScreen.kt`.
 */
enum class SortOption(val value: String, @StringRes val labelRes: Int) {
  REPORT_COUNT("REPORT_COUNT", R.string.sort_report_count),
  PRICE_ASC("PRICE_ASC", R.string.sort_price_asc),
  QUALITY("QUALITY", R.string.sort_quality),
  LAST_REPORTED("LAST_REPORTED", R.string.sort_last_reported),
  NAME("NAME", R.string.sort_name),
}

private const val PAGE_SIZE = 20

class SearchViewModel(private val graphQlClient: GraphQlClient) : ViewModel() {

  var query by mutableStateOf("")
  var selectedStoreId by mutableStateOf<String?>(null)
    private set
  var selectedCity by mutableStateOf<String?>(null)
    private set
  var sort by mutableStateOf(SortOption.REPORT_COUNT)
    private set

  var items by mutableStateOf<List<ProductSearchItem>>(emptyList())
    private set
  var totalCount by mutableStateOf(0)
    private set
  var hasMore by mutableStateOf(false)
    private set
  var loading by mutableStateOf(false)
    private set
  var errorMessage by mutableStateOf<UiText?>(null)
    private set
  var hasSearched by mutableStateOf(false)
    private set

  var facets by mutableStateOf(SearchFacets())
    private set

  init {
    viewModelScope.launch {
      // Filtry jsou volitelný doplněk hledání — chyba tady nesmí zablokovat samotné hledání.
      runCatching { facets = graphQlClient.searchFacets() }
    }
  }

  fun search() {
    if (query.isBlank()) {
      items = emptyList()
      totalCount = 0
      hasMore = false
      hasSearched = false
      return
    }
    hasSearched = true
    loading = true
    errorMessage = null
    viewModelScope.launch {
      try {
        val result = graphQlClient.searchProducts(
          query = query.trim(),
          storeId = selectedStoreId,
          city = selectedCity,
          sort = sort.value,
          first = PAGE_SIZE,
          offset = 0,
        )
        items = result.items
        totalCount = result.totalCount
        hasMore = result.hasMore
      } catch (e: Exception) {
        errorMessage = UiText.Res(R.string.search_failed)
      } finally {
        loading = false
      }
    }
  }

  fun loadMore() {
    if (loading || !hasMore) return
    loading = true
    viewModelScope.launch {
      try {
        val result = graphQlClient.searchProducts(
          query = query.trim(),
          storeId = selectedStoreId,
          city = selectedCity,
          sort = sort.value,
          first = PAGE_SIZE,
          offset = items.size,
        )
        items = items + result.items
        totalCount = result.totalCount
        hasMore = result.hasMore
      } catch (e: Exception) {
        // Donačítání zticha selže — první stránka výsledků zůstane vidět.
      } finally {
        loading = false
      }
    }
  }

  fun onStoreChange(storeId: String?) {
    selectedStoreId = storeId
    search()
  }

  fun onCityChange(city: String?) {
    selectedCity = city
    search()
  }

  fun onSortChange(newSort: SortOption) {
    sort = newSort
    search()
  }
}
