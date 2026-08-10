package cz.kvalitacena.ui.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.R
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.Photo
import cz.kvalitacena.network.Store
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.common.toUiText
import kotlinx.coroutines.launch

/** Webový protějšek: frontend features/store-detail/store-detail-page.ts. */
class StoreDetailViewModel(
  private val graphQlClient: GraphQlClient,
  private val storeId: String,
) : ViewModel() {

  var loading by mutableStateOf(true)
    private set
  var store by mutableStateOf<Store?>(null)
    private set
  var notFound by mutableStateOf(false)
    private set

  var flagging by mutableStateOf(false)
    private set
  var flagMessage by mutableStateOf<UiText?>(null)
    private set

  init {
    loadStore()
  }

  fun loadStore() {
    loading = true
    viewModelScope.launch {
      try {
        val found = graphQlClient.storeById(storeId)
        store = found
        notFound = found == null
      } catch (e: Exception) {
        notFound = true
      } finally {
        loading = false
      }
    }
  }

  /** Po uploadu/smazání/přeřazení fotky (PhotoGallery/PhotoPicker) — jen lokální stav. */
  fun onPhotosChange(photos: List<Photo>) {
    store = store?.copy(photos = photos)
  }

  /** Po návratu z editace (StoreFormScreen storeId != null). */
  fun onStoreUpdated(updated: Store) {
    // updateStore (GraphQL) nevrací photos (STORE_FIELDS, ne STORE_DETAIL_FIELDS) — zachová
    // dosud načtenou galerii beze změny, stejný princip jako web store-detail-page.ts.
    store = store?.let { updated.copy(photos = it.photos) } ?: updated
  }

  /** Hlasuje se o FAKTU, nikdy o ČLOVĚKU (docs/reputace.md, "Nesouhlas se vyjadřuje k faktu"). */
  fun flagStore() {
    flagging = true
    flagMessage = null
    viewModelScope.launch {
      try {
        val result = graphQlClient.flagRecord("STORE", storeId)
        flagMessage = UiText.Res(
          if (result.hidden) R.string.store_report_hidden else R.string.report_acknowledged,
        )
      } catch (e: Exception) {
        flagMessage = e.toUiText()
      } finally {
        flagging = false
      }
    }
  }
}
