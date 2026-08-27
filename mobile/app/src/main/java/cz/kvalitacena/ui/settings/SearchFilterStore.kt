package cz.kvalitacena.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val PREFS_NAME = "kvalita_a_cena_settings"
private const val KEY_CITY = "search_city"
private const val KEY_STORE_ID = "search_store_id"
private const val KEY_CATEGORY_ID = "search_category_id"
private const val KEY_SORT = "search_sort"

/**
 * Filtry obrazovky Hledat (obchod, město, řazení) přežívající přepnutí záložky —
 * `MainActivity.AppBottomBar` při každém kliknutí maže celý navigační zásobník včetně
 * startDestination (`popUpTo(...) { inclusive = true }`), takže `SearchViewModel` s ním zaniká
 * a filtry v `mutableStateOf` na ViewModelu by se ztratily. Hledaný text se sem záměrně
 * NEUKLÁDÁ — jen filtry, viz [cz.kvalitacena.ui.search.SearchViewModel]. Stejný vzor jako
 * [CountryStore] — obyčejné SharedPreferences, appka nemá DataStore/Room.
 */
class SearchFilterStore(context: Context) {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  var city: String? by mutableStateOf(prefs.getString(KEY_CITY, null))
    private set
  var storeId: String? by mutableStateOf(prefs.getString(KEY_STORE_ID, null))
    private set
  var categoryId: String? by mutableStateOf(prefs.getString(KEY_CATEGORY_ID, null))
    private set
  var sort: String by mutableStateOf(prefs.getString(KEY_SORT, null) ?: "REPORT_COUNT")
    private set

  fun selectCity(value: String?) {
    city = value
    prefs.edit().apply { if (value == null) remove(KEY_CITY) else putString(KEY_CITY, value) }.apply()
  }

  fun selectStoreId(value: String?) {
    storeId = value
    prefs.edit().apply { if (value == null) remove(KEY_STORE_ID) else putString(KEY_STORE_ID, value) }.apply()
  }

  fun selectCategoryId(value: String?) {
    categoryId = value
    prefs.edit().apply { if (value == null) remove(KEY_CATEGORY_ID) else putString(KEY_CATEGORY_ID, value) }.apply()
  }

  fun selectSort(value: String) {
    sort = value
    prefs.edit().putString(KEY_SORT, value).apply()
  }

  /** Zahodí uloženou hodnotu, která v aktuálním číselníku (jiná země, obchod bez cen) už neexistuje. */
  fun dropCityIfMissing(known: List<String>) {
    if (city != null && city !in known) selectCity(null)
  }

  fun dropStoreIfMissing(known: List<String>) {
    if (storeId != null && storeId !in known) selectStoreId(null)
  }

  /** Číselník kategorií se mezi vydáními může přečíslovat — uložené id, které v aktuálním
   *  stromu není, by jinak backend odmítl (CATEGORY_NOT_FOUND) a appka by přestala hledat. */
  fun dropCategoryIfMissing(known: List<String>) {
    if (categoryId != null && categoryId !in known) selectCategoryId(null)
  }
}
