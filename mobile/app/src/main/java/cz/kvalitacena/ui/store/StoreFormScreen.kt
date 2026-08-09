package cz.kvalitacena.ui.store

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer
import cz.kvalitacena.location.getCurrentLocation
import cz.kvalitacena.network.GeocodeCandidate
import cz.kvalitacena.ui.common.LocationMap
import cz.kvalitacena.ui.common.NavigationResults
import cz.kvalitacena.ui.common.SingleLineTextField
import kotlinx.coroutines.launch

/**
 * Založení provozovny — vyplní se název/adresa, volitelně IČO (s předvyplněním z ARES) a
 * volitelně souřadnice (geokódování přes server, mapa nebo aktuální poloha). Obchod jde uložit
 * i bez souřadnic (docs/datovy-model.md) — doplní se později. Po úspěchu se výsledek předá
 * zpátky přes [NavigationResults] a obrazovka se zavře (`onDone`).
 *
 * Se zadaným [storeId] přejde do režimu editace existující provozovny — používá ji
 * StoreDetailScreen. Webový protějšek: frontend shared/store-form.ts.
 */
@Composable
fun StoreFormScreen(storeId: String? = null, onDone: () -> Unit) {
  val viewModel: StoreFormViewModel = viewModel(
    factory = viewModelFactory { initializer { StoreFormViewModel(AppContainer.graphQlClient, storeId) } },
  )
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  LaunchedEffect(viewModel.created) {
    viewModel.created?.let {
      if (viewModel.isEditing) NavigationResults.updatedStore = it else NavigationResults.newStore = it
      onDone()
    }
  }

  val locationPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) {
      scope.launch {
        getCurrentLocation(context)?.let { viewModel.useMyLocation(it.latitude, it.longitude) }
      }
    }
  }

  fun useMyLocation() {
    val hasPermission = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasPermission) {
      locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
      return
    }
    scope.launch {
      getCurrentLocation(context)?.let { viewModel.useMyLocation(it.latitude, it.longitude) }
    }
  }

  if (viewModel.loadingExisting) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    return
  }

  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    Text(if (viewModel.isEditing) "Upravit obchod" else "Nový obchod", style = MaterialTheme.typography.headlineSmall)
    Gap()

    SingleLineTextField(
      value = viewModel.name,
      onValueChange = viewModel::onNameChange,
      label = "Název obchodu",
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()
    SingleLineTextField(
      value = viewModel.street,
      onValueChange = { viewModel.street = it },
      label = "Ulice a číslo (volitelné)",
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()
    SingleLineTextField(
      value = viewModel.city,
      onValueChange = viewModel::onCityChange,
      label = "Město",
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()
    SingleLineTextField(
      value = viewModel.postalCode,
      onValueChange = { viewModel.postalCode = it },
      label = "PSČ (volitelné)",
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()

    if (viewModel.similarStores.isNotEmpty()) {
      Text(
        "Nemyslíš spíš některý z těchhle už existujících obchodů?",
        style = MaterialTheme.typography.bodyMedium,
      )
      viewModel.similarStores.forEach { store ->
        Text(
          "${store.name} — ${store.city}${store.street?.let { ", $it" } ?: ""}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Gap()
    }

    HorizontalDivider()
    Gap()

    Text("IČO (volitelné)", style = MaterialTheme.typography.titleMedium)
    Text(
      "Pro podnikovou prodejnu nebo OSVČ, kde samotný název nestačí k jednoznačné identifikaci.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Gap()
    Row(verticalAlignment = Alignment.CenterVertically) {
      SingleLineTextField(
        value = viewModel.ico,
        onValueChange = { viewModel.ico = it },
        label = "IČO",
        isError = viewModel.ico.isNotBlank() && !isIcoShapeValid(viewModel.ico),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(1f),
      )
      Button(onClick = { viewModel.lookupIco() }, enabled = !viewModel.icoLookupLoading) {
        if (viewModel.icoLookupLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
        else Text("Načíst z ARES")
      }
    }
    viewModel.icoLookupError?.let {
      Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Gap()

    HorizontalDivider()
    Gap()

    Text("Souřadnice (volitelné)", style = MaterialTheme.typography.titleMedium)
    Text(
      "Bez souřadnic obchod nenajde \"Najít v okolí\", jen ruční hledání podle názvu — dají se doplnit i později.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Gap()
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { viewModel.geocode() }, enabled = viewModel.city.isNotBlank() && !viewModel.geocoding) {
        if (viewModel.geocoding) CircularProgressIndicator(modifier = Modifier.size(20.dp))
        else Text("Najít souřadnice")
      }
      OutlinedButton(onClick = { useMyLocation() }, enabled = !viewModel.locating) {
        if (viewModel.locating) CircularProgressIndicator(modifier = Modifier.size(20.dp))
        else Text("Použít mou polohu")
      }
    }
    Gap()

    if (viewModel.geocodeCandidates.isNotEmpty()) {
      viewModel.geocodeCandidates.forEach { candidate ->
        CandidateRow(
          candidate = candidate,
          selected = viewModel.selectedCandidate == candidate,
          onSelect = { viewModel.selectCandidate(candidate) },
        )
      }
      viewModel.geocodeAttribution?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Gap()
    }

    if (viewModel.manualLat != null && viewModel.manualLon != null && viewModel.selectedCandidate == null) {
      Text(
        "Použije se poloha z mapy níž / tvoje aktuální poloha.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Gap()
    }

    LocationMap(
      lat = viewModel.selectedCandidate?.lat ?: viewModel.manualLat,
      lon = viewModel.selectedCandidate?.lon ?: viewModel.manualLon,
      editable = true,
      onPointSelected = { lat, lon -> viewModel.onMapPointSelected(lat, lon) },
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()

    viewModel.saveError?.let {
      Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      Gap()
    }

    Button(
      onClick = { viewModel.submit() },
      enabled = isStoreFormValid(viewModel.name, viewModel.city) &&
        isIcoShapeValid(viewModel.ico) && !viewModel.saving,
      modifier = Modifier.fillMaxWidth(),
    ) {
      if (viewModel.saving) CircularProgressIndicator(modifier = Modifier.size(20.dp))
      else Text(if (viewModel.isEditing) "Uložit změny" else "Založit obchod")
    }
    Gap()
    OutlinedButton(onClick = onDone, enabled = !viewModel.saving, modifier = Modifier.fillMaxWidth()) {
      Text(if (viewModel.isEditing) "Zrušit" else "Zpět bez založení")
    }
  }
}

@Composable
private fun CandidateRow(candidate: GeocodeCandidate, selected: Boolean, onSelect: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .selectable(selected = selected, onClick = onSelect)
      .padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = onSelect)
    Text(candidate.displayName, style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun Gap() {
  androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
}
