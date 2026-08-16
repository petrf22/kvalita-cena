package cz.kvalitacena.ui.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.ui.common.LocationMap
import cz.kvalitacena.ui.common.companyIdLabelRes
import cz.kvalitacena.ui.common.NavigationResults
import cz.kvalitacena.ui.common.PhotoGallery
import cz.kvalitacena.ui.common.PhotoPicker
import cz.kvalitacena.ui.common.openMap

/**
 * Detail provozovny — adresa, mapa, fotky, editace a nahlášení. Doplňuje CLAUDE.md, „inline
 * úprava obchodu v UI zatím chybí" — tahle obrazovka mutace `updateStore`/`flagRecord`
 * konečně volá. Webový protějšek: frontend features/store-detail/store-detail-page.ts.
 */
@Composable
fun StoreDetailScreen(storeId: String, onEditStore: (String) -> Unit) {
  val viewModel: StoreDetailViewModel = viewModel(
    factory = viewModelFactory { initializer { StoreDetailViewModel(AppContainer.graphQlClient, storeId) } },
  )
  val context = LocalContext.current
  val accessToken by AppContainer.authRepository.accessToken.collectAsState()
  val isLoggedIn = accessToken != null

  // Po návratu z editace (StoreFormScreen storeId != null) vyzvedne výsledek stejným vzorem
  // jako PriceEntryScreen NavigationResults.newStore/newProduct.
  LaunchedEffect(Unit) {
    NavigationResults.updatedStore?.let {
      viewModel.onStoreUpdated(it)
      NavigationResults.updatedStore = null
    }
  }

  when {
    viewModel.loading -> {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }

    viewModel.notFound -> {
      Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.store_not_found), style = MaterialTheme.typography.bodyLarge)
      }
    }

    else -> {
      val store = viewModel.store!!
      Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(store.name, style = MaterialTheme.typography.headlineSmall)
        // Kód země se přilepí jen když se liší od domácí země vieweru (docs/lokalizace.md,
        // "Country selector v UI") — webový protějšek: store-detail-page.html.
        val cityWithCountry = if (store.country != AppContainer.countryStore.country) {
          "${store.city} (${store.country})"
        } else {
          store.city
        }
        val addressLine = listOfNotNull(store.chain?.name, listOfNotNull(store.street, cityWithCountry).joinToString(", "))
          .joinToString(" · ")
        if (addressLine.isNotBlank()) Text(addressLine, style = MaterialTheme.typography.bodyMedium)

        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          if (!store.verified) AssistChip(onClick = {}, label = { Text(stringResource(R.string.common_unverified)) })
          if (store.editedByMe) AssistChip(onClick = {}, label = { Text(stringResource(R.string.store_edited_by_me)) })
          if (store.pendingConfirmation) {
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.store_pending_confirmation)) })
          }
        }
        if (isLoggedIn) {
          Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onEditStore(storeId) }) { Text(stringResource(R.string.common_edit)) }
            TextButton(onClick = { viewModel.flagStore() }, enabled = !viewModel.flagging) {
              Text(stringResource(R.string.common_report))
            }
          }
        }
        viewModel.flagMessage?.let {
          Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Gap()
        HorizontalDivider()
        Gap()

        Text(stringResource(R.string.store_photos_title), style = MaterialTheme.typography.titleMedium)
        Gap()
        PhotoGallery(
          photos = store.photos,
          onPhotosChange = viewModel::onPhotosChange,
          modifier = Modifier.fillMaxWidth(),
        )
        if (isLoggedIn) {
          PhotoPicker(
            recordType = "STORE",
            recordId = storeId,
            existingPhotoCount = store.photos.size,
            onUploaded = { photo -> viewModel.onPhotosChange(store.photos + photo) },
            modifier = Modifier.padding(top = 4.dp),
          )
        }
        Gap()
        HorizontalDivider()
        Gap()

        Text(stringResource(R.string.store_location_title), style = MaterialTheme.typography.titleMedium)
        Gap()
        if (store.lat != null && store.lon != null) {
          LocationMap(lat = store.lat, lon = store.lon, editable = false, modifier = Modifier.fillMaxWidth())
          Gap()
          OutlinedButton(onClick = { openMap(context, store.lat, store.lon, store.name) }) {
            Text(stringResource(R.string.store_open_in_map_app))
          }
        } else {
          Text(stringResource(R.string.store_no_location), style = MaterialTheme.typography.bodyMedium)
        }

        if (!store.ico.isNullOrBlank()) {
          Gap()
          HorizontalDivider()
          Gap()
          Text(stringResource(R.string.store_operator_title), style = MaterialTheme.typography.titleMedium)
          Text(
            "${stringResource(companyIdLabelRes(store.country))}: ${store.ico}",
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
    }
  }
}

@Composable
private fun Gap() {
  androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
}
