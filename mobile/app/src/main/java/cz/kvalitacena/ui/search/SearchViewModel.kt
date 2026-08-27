package cz.kvalitacena.ui.search

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.R
import cz.kvalitacena.auth.AuthRepository
import cz.kvalitacena.network.Category
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.ProductSearchItem
import cz.kvalitacena.network.SearchFacets
import cz.kvalitacena.ui.common.CategoryChoice
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.common.categoryBreadcrumb
import cz.kvalitacena.ui.settings.CountryStore
import cz.kvalitacena.ui.settings.SearchFilterStore
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

class SearchViewModel(
  private val graphQlClient: GraphQlClient,
  private val authRepository: AuthRepository,
  private val countryStore: CountryStore,
  private val filterStore: SearchFilterStore,
) : ViewModel() {

  // Hledaný text se NEUKLÁDÁ — jen filtry, viz SearchFilterStore. Filtry přežívají přepnutí
  // záložky (appka jinak maže celý navigační zásobník, MainActivity.AppBottomBar), proto se
  // počáteční hodnota bere rovnou z uložené preference, ne z pevné výchozí.
  var query by mutableStateOf("")
  var selectedStoreId by mutableStateOf(filterStore.storeId)
    private set
  var selectedCity by mutableStateOf(filterStore.city)
    private set
  var selectedCategoryId by mutableStateOf(filterStore.categoryId)
    private set
  var sort by mutableStateOf(SortOption.entries.firstOrNull { it.value == filterStore.sort } ?: SortOption.REPORT_COUNT)
    private set

  var categories by mutableStateOf<List<Category>>(emptyList())
    private set
  var categoryQuery by mutableStateOf("")

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
      // country jde vždy explicitně z CountryStore (appka je autoritativní, docs/lokalizace.md).
      runCatching { facets = graphQlClient.searchFacets(country = countryStore.country) }
        .onSuccess {
          // Uložené město/obchod nemusí být v aktuálním číselníku (jiná země přes CountryStore,
          // obchod mezitím bez cen) — jinak by filtr tiše omezoval výsledky, aniž by šel v UI vidět.
          filterStore.dropCityIfMissing(facets.cities)
          filterStore.dropStoreIfMissing(facets.stores.map { it.id })
          if (selectedCity != filterStore.city) selectedCity = filterStore.city
          if (selectedStoreId != filterStore.storeId) selectedStoreId = filterStore.storeId
        }
    }
    viewModelScope.launch {
      // Číselník kategorií je fixní/kurátorský (docs/rozvoj.md), ale mezi vydáními se může
      // přečíslovat — uložené id, které v aktuálním stromu není, by backend odmítl jako
      // CATEGORY_NOT_FOUND a appka by přestala hledat, dokud by si uživatel filtr sám nesmazal.
      runCatching { categories = graphQlClient.categories() }
        .onSuccess {
          filterStore.dropCategoryIfMissing(categories.map { it.id })
          if (selectedCategoryId != filterStore.categoryId) selectedCategoryId = filterStore.categoryId
          // Pole hledání kategorie ukazuje breadcrumb aktivního filtru i po obnovení appky
          // (SearchFilterStore) — jinak by bylo prázdné, i když filtr v pozadí platí.
          selectedCategoryId?.let { id ->
            categories.find { it.id == id }?.let { categoryQuery = categoryBreadcrumb(it, categories) }
          }
        }
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
        // Vlastní nepotvrzené (DRAFT) zboží uvidí ve výsledcích jen přihlášený autor — appka
        // odpaluje obnovení přihlášení při startu bez čekání (MainActivity.kt), takže dotaz
        // hned po startu appky by se bez tohohle čekání mohl zeptat ještě jako anonym.
        authRepository.awaitInitialRefresh()
        val result = graphQlClient.searchProducts(
          query = query.trim(),
          storeId = selectedStoreId,
          city = selectedCity,
          categoryId = selectedCategoryId,
          country = countryStore.country,
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
          categoryId = selectedCategoryId,
          country = countryStore.country,
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
    filterStore.selectStoreId(storeId)
    search()
  }

  fun onCityChange(city: String?) {
    selectedCity = city
    filterStore.selectCity(city)
    search()
  }

  /** Výběr z nabídky `SearchableDropdown` — pole pak ukazuje breadcrumb ("Potraviny › Mléčné
   *  výrobky › Máslo"), stejný vzor jako ProductFormViewModel.onCategorySelected. */
  fun onCategorySelected(choice: CategoryChoice) {
    selectedCategoryId = choice.category.id
    categoryQuery = categoryBreadcrumb(choice.category, categories)
    filterStore.selectCategoryId(choice.category.id)
    search()
  }

  fun onCategoryQueryChange(value: String) {
    categoryQuery = value
  }

  /** Zrušení filtru kategorie — `SearchableDropdown` sám "vymazat" neumí, proto samostatné tlačítko v UI. */
  fun clearCategory() {
    selectedCategoryId = null
    categoryQuery = ""
    filterStore.selectCategoryId(null)
    search()
  }

  fun onSortChange(newSort: SortOption) {
    sort = newSort
    filterStore.selectSort(newSort.value)
    search()
  }

  /** Vyzvednutí kódu/názvu z PriceEntryScreen ("Hledat ceny tohoto zboží") — viz NavigationResults.searchQuery. */
  fun applyExternalQuery(newQuery: String) {
    query = newQuery
    search()
  }
}
