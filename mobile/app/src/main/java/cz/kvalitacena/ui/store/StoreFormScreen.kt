package cz.kvalitacena.ui.store

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.location.LOCATION_PERMISSIONS
import cz.kvalitacena.location.LocationResult
import cz.kvalitacena.location.getCurrentLocation
import cz.kvalitacena.location.hasAnyLocationPermission
import cz.kvalitacena.location.hasOnlyApproximateLocation
import cz.kvalitacena.network.GeocodeCandidate
import cz.kvalitacena.ui.common.KNOWN_COUNTRIES
import cz.kvalitacena.ui.common.LocationMap
import cz.kvalitacena.ui.common.NavigationResults
import cz.kvalitacena.ui.common.SearchableDropdown
import cz.kvalitacena.ui.common.SingleLineTextField
import cz.kvalitacena.ui.common.companyIdLabelRes
import cz.kvalitacena.ui.common.countryNameRes
import cz.kvalitacena.ui.common.hasCompanyRegistry
import cz.kvalitacena.ui.navigation.LocalNavigationExitGuard
import cz.kvalitacena.ui.navigation.ReportUnsavedChanges
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
  var formDirty by rememberSaveable { mutableStateOf(false) }
  val exitGuard = LocalNavigationExitGuard.current
  ReportUnsavedChanges(formDirty && viewModel.created == null)

  // Kandidát z nabídky OpenStreetMap (StorePicker) — vyzvedne se stejně jako newStore/newProduct
  // na PriceEntryScreen, jen opačným směrem: tenhle jde DO formuláře.
  LaunchedEffect(Unit) {
    NavigationResults.osmStoreCandidate?.let { candidate ->
      formDirty = true
      viewModel.applyOsmCandidate(candidate, NavigationResults.osmStoreAttribution)
      NavigationResults.osmStoreCandidate = null
      NavigationResults.osmStoreAttribution = null
    }
  }

  LaunchedEffect(viewModel.created) {
    viewModel.created?.let {
      formDirty = false
      if (viewModel.isEditing) NavigationResults.updatedStore = it else NavigationResults.newStore = it
      onDone()
    }
  }

  // Obě oprávnění naráz — souřadnice PROVOZOVNY nastavené z přibližné polohy by byly
  // o kilometry vedle (location/LocationPermissions.kt).
  val locationPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) { result ->
    if (result.values.any { it }) {
      scope.launch { useResolvedLocation(context, viewModel) { formDirty = true } }
    }
  }

  fun useMyLocation() {
    if (!hasAnyLocationPermission(context)) {
      locationPermissionLauncher.launch(LOCATION_PERMISSIONS)
      return
    }
    scope.launch { useResolvedLocation(context, viewModel) { formDirty = true } }
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

    // ODbL vyžaduje, aby appka u převzatých dat vždy uvedla zdroj (docs/datovy-model.md).
    // Text chodí ze serveru, protože je lokalizovaný (docs/lokalizace.md).
    viewModel.osmCandidate?.let {
      Text(stringResource(R.string.store_form_prefilled_from_osm), style = MaterialTheme.typography.bodyMedium)
      viewModel.osmAttribution?.let { attribution ->
        Text(
          attribution,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Gap()
    }

    SearchableDropdown(
      query = viewModel.chainQuery,
      onQueryChange = { formDirty = true; viewModel.onChainQueryChange(it) },
      suggestions = viewModel.chainSuggestions,
      onSelect = { formDirty = true; viewModel.onChainSelect(it) },
      itemLabel = { it.name },
      label = stringResource(R.string.store_form_chain_label),
      loading = viewModel.chainSearching,
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()

    SingleLineTextField(
      value = viewModel.name,
      onValueChange = { formDirty = true; viewModel.onNameChange(it) },
      label = stringResource(R.string.store_form_name_label),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()
    SingleLineTextField(
      value = viewModel.street,
      onValueChange = { formDirty = true; viewModel.onStreetChange(it) },
      label = stringResource(R.string.store_form_street_label),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()
    SingleLineTextField(
      value = viewModel.city,
      onValueChange = { formDirty = true; viewModel.onCityChange(it) },
      label = stringResource(R.string.store_form_city_label),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()
    SingleLineTextField(
      value = viewModel.postalCode,
      onValueChange = { formDirty = true; viewModel.onPostalCodeChange(it) },
      label = stringResource(R.string.store_form_postal_code_label),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()
    SingleLineTextField(
      value = viewModel.url,
      onValueChange = { formDirty = true; viewModel.url = it },
      label = stringResource(R.string.store_form_url_label),
      isError = viewModel.url.isNotBlank() && !isUrlShapeValid(viewModel.url),
      supportingText = if (viewModel.url.isNotBlank() && !isUrlShapeValid(viewModel.url)) {
        { Text(stringResource(R.string.store_form_url_invalid)) }
      } else null,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()

    CountryDropdown(selected = viewModel.country, onSelect = { formDirty = true; viewModel.onCountryChange(it) })
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
        onValueChange = { formDirty = true; viewModel.ico = it },
        label = companyIdLabel,
        isError = viewModel.ico.isNotBlank() && !isIcoShapeValid(viewModel.ico, viewModel.country),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(1f),
      )
      if (hasCompanyRegistry(viewModel.country)) {
        Button(onClick = { viewModel.lookupIco(onFound = { formDirty = true }) }, enabled = !viewModel.icoLookupLoading) {
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
    if (viewModel.geocoding) {
      Text(
        stringResource(R.string.store_location_searching),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Gap()
    }
    // Jediný nález se použije sám (StoreFormViewModel.geocode) — uživatel se nemá co ptát,
    // jen se mu řekne, co se stalo, a smí to přebít klepnutím do mapy.
    if (!viewModel.geocoding && viewModel.selectedCandidate != null && viewModel.geocodeCandidates.isEmpty()) {
      Text(
        stringResource(R.string.store_location_found_from_address),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      viewModel.geocodeAttribution?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Gap()
    }
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
      // Dohledání běží samo po vyplnění adresy — tohle je jen ruční zopakování, když tiše
      // selhalo (docs/design.md: chybový stav musí jít zopakovat).
      OutlinedButton(onClick = { viewModel.geocode() }, enabled = viewModel.city.isNotBlank() && !viewModel.geocoding) {
        if (viewModel.geocoding) CircularProgressIndicator(modifier = Modifier.size(20.dp))
        else Text(stringResource(R.string.store_location_search_again))
      }
      OutlinedButton(onClick = { useMyLocation() }, enabled = !viewModel.locating) {
        if (viewModel.locating) CircularProgressIndicator(modifier = Modifier.size(20.dp))
        else Text(stringResource(R.string.store_location_use_my_location))
      }
    }
    // Souřadnice provozovny jsou trvalý údaj pro celou komunitu — uložit sem bod posunutý
    // systémem o kilometry je horší než ho nemít vůbec.
    if (hasOnlyApproximateLocation(context)) {
      Text(
        stringResource(R.string.store_location_approximate_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Gap()

    if (viewModel.geocodeCandidates.isNotEmpty()) {
      viewModel.geocodeCandidates.forEach { candidate ->
        CandidateRow(
          candidate = candidate,
          selected = viewModel.selectedCandidate == candidate,
          onSelect = { formDirty = true; viewModel.selectCandidate(candidate) },
        )
      }
      viewModel.geocodeAttribution?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Gap()
    }

    // U bodu z OSM kandidáta se nevypisuje — o jeho původu mluví už hlavička formuláře.
    if (viewModel.manualLat != null && viewModel.manualLon != null &&
      viewModel.selectedCandidate == null && viewModel.osmCandidate == null
    ) {
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
      onPointSelected = { lat, lon -> formDirty = true; viewModel.onMapPointSelected(lat, lon) },
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()

    viewModel.saveError?.let {
      Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      // Duplicita není slepá ulička — obchod, se kterým se ten nový srazil, jde rovnou použít.
      if (viewModel.duplicateStoreId != null) {
        OutlinedButton(
          onClick = { viewModel.useDuplicateStore() },
          enabled = !viewModel.loadingDuplicate,
          modifier = Modifier.fillMaxWidth(),
        ) {
          if (viewModel.loadingDuplicate) CircularProgressIndicator(modifier = Modifier.size(20.dp))
          else Text(stringResource(R.string.store_form_use_existing_store))
        }
      }
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
    OutlinedButton(
      onClick = { exitGuard.requestNavigation(onDone) },
      enabled = !viewModel.saving,
      modifier = Modifier.fillMaxWidth(),
    ) {
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

/**
 * Nedostupná poloha se ve formuláři ignoruje potichu — bod si uživatel může dát klepnutím do
 * mapy a adresu vyplnit ručně, takže hláška by tu jen překážela. (Ve výběru obchodu je to
 * naopak, tam bez polohy není co nabídnout — viz StorePicker/NearbyState.)
 */
private suspend fun useResolvedLocation(
  context: android.content.Context,
  viewModel: StoreFormViewModel,
  onFound: () -> Unit,
) {
  val result = getCurrentLocation(context)
  if (result is LocationResult.Found) {
    onFound()
    viewModel.useMyLocation(result.lat, result.lon)
  }
}
