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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.location.getCurrentLocation
import cz.kvalitacena.network.GeocodeCandidate
import cz.kvalitacena.ui.common.KNOWN_COUNTRIES
import cz.kvalitacena.ui.common.LocationMap
import cz.kvalitacena.ui.common.NavigationResults
import cz.kvalitacena.ui.common.SearchableDropdown
import cz.kvalitacena.ui.common.SingleLineTextField
import cz.kvalitacena.ui.common.companyIdLabelRes
import cz.kvalitacena.ui.common.countryNameRes
import cz.kvalitacena.ui.common.hasCompanyRegistry
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreFormScreen(storeId: String? = null, onDone: () -> Unit) {
  val viewModel: StoreFormViewModel = viewModel(
    factory = viewModelFactory {
      initializer { StoreFormViewModel(AppContainer.graphQlClient, storeId, AppContainer.countryStore) }
    },
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
    Text(
      stringResource(if (viewModel.isEditing) R.string.store_form_edit_title else R.string.store_form_create_title),
      style = MaterialTheme.typography.headlineSmall,
    )
    Gap()

    SearchableDropdown(
      query = viewModel.chainQuery,
      onQueryChange = viewModel::onChainQueryChange,
      suggestions = viewModel.chainSuggestions,
      onSelect = viewModel::onChainSelect,
      itemLabel = { it.name },
      label = stringResource(R.string.store_form_chain_label),
      loading = viewModel.chainSearching,
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()

    SingleLineTextField(
      value = viewModel.name,
      onValueChange = viewModel::onNameChange,
      label = stringResource(R.string.store_form_name_label),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()
    SingleLineTextField(
      value = viewModel.street,
      onValueChange = { viewModel.street = it },
      label = stringResource(R.string.store_form_street_label),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()
    SingleLineTextField(
      value = viewModel.city,
      onValueChange = viewModel::onCityChange,
      label = stringResource(R.string.store_form_city_label),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()
    SingleLineTextField(
      value = viewModel.postalCode,
      onValueChange = { viewModel.postalCode = it },
      label = stringResource(R.string.store_form_postal_code_label),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()
    SingleLineTextField(
      value = viewModel.url,
      onValueChange = { viewModel.url = it },
      label = stringResource(R.string.store_form_url_label),
      isError = viewModel.url.isNotBlank() && !isUrlShapeValid(viewModel.url),
      supportingText = if (viewModel.url.isNotBlank() && !isUrlShapeValid(viewModel.url)) {
        { Text(stringResource(R.string.store_form_url_invalid)) }
      } else null,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()

    CountryDropdown(selected = viewModel.country, onSelect = { viewModel.country = it })
    Gap()

    if (viewModel.similarStores.isNotEmpty()) {
      Text(stringResource(R.string.store_form_similar_warning), style = MaterialTheme.typography.bodyMedium)
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

    val companyIdLabel = stringResource(companyIdLabelRes(viewModel.country))
    Text(
      stringResource(R.string.store_company_id_section_title, companyIdLabel),
      style = MaterialTheme.typography.titleMedium,
    )
    Text(
      stringResource(R.string.store_company_id_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Gap()
    Row(verticalAlignment = Alignment.CenterVertically) {
      SingleLineTextField(
        value = viewModel.ico,
        onValueChange = { viewModel.ico = it },
        label = companyIdLabel,
        isError = viewModel.ico.isNotBlank() && !isIcoShapeValid(viewModel.ico, viewModel.country),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(1f),
      )
      if (hasCompanyRegistry(viewModel.country)) {
        Button(onClick = { viewModel.lookupIco() }, enabled = !viewModel.icoLookupLoading) {
          if (viewModel.icoLookupLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
          else Text(stringResource(R.string.store_company_id_load_from_registry))
        }
      }
    }
    viewModel.icoLookupError?.let {
      Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Gap()

    HorizontalDivider()
    Gap()

    Text(stringResource(R.string.store_location_section_title), style = MaterialTheme.typography.titleMedium)
    Text(
      stringResource(R.string.store_location_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Gap()
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { viewModel.geocode() }, enabled = viewModel.city.isNotBlank() && !viewModel.geocoding) {
        if (viewModel.geocoding) CircularProgressIndicator(modifier = Modifier.size(20.dp))
        else Text(stringResource(R.string.store_location_find_coordinates))
      }
      OutlinedButton(onClick = { useMyLocation() }, enabled = !viewModel.locating) {
        if (viewModel.locating) CircularProgressIndicator(modifier = Modifier.size(20.dp))
        else Text(stringResource(R.string.store_location_use_my_location))
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
        stringResource(R.string.store_location_will_use_map_position),
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
      Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      Gap()
    }

    Button(
      onClick = { viewModel.submit() },
      enabled = isStoreFormValid(viewModel.name, viewModel.city) &&
        isIcoShapeValid(viewModel.ico, viewModel.country) &&
        isUrlShapeValid(viewModel.url) && !viewModel.saving,
      modifier = Modifier.fillMaxWidth(),
    ) {
      if (viewModel.saving) CircularProgressIndicator(modifier = Modifier.size(20.dp))
      else Text(stringResource(if (viewModel.isEditing) R.string.store_form_save_changes else R.string.store_form_create))
    }
    Gap()
    OutlinedButton(onClick = onDone, enabled = !viewModel.saving, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(if (viewModel.isEditing) R.string.common_cancel else R.string.store_form_back_without_creating))
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

/**
 * Výběr země obchodu — appka zatím zná jen CZ/SK/PL ([KNOWN_COUNTRIES]). Na rozdíl od zbytku
 * formuláře jde tahle hodnota při editaci rovnou do globální provozovny, gatováno důvěrou
 * autora (docs/lokalizace.md, "Country selector v UI") — nedůvěryhodný autor dostane chybu ze
 * serveru (viz `saveError`), formulář to preventivně neomezuje. Stejná `ExposedDropdownMenuBox`
 * konvence jako `PriceKindDropdown`/`CurrencyDropdown`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryDropdown(selected: String, onSelect: (String) -> Unit) {
  var expanded by remember { mutableStateOf(false) }

  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
    SingleLineTextField(
      value = stringResource(countryNameRes(selected)),
      onValueChange = {},
      readOnly = true,
      label = stringResource(R.string.store_form_country_label),
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      KNOWN_COUNTRIES.forEach { code ->
        DropdownMenuItem(
          text = { Text(stringResource(countryNameRes(code))) },
          onClick = {
            onSelect(code)
            expanded = false
          },
        )
      }
    }
  }
}
