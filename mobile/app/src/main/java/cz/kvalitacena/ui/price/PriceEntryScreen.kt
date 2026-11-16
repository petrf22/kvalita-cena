package cz.kvalitacena.ui.price

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer
import cz.kvalitacena.network.Product
import cz.kvalitacena.price.PriceWork
import cz.kvalitacena.R
import cz.kvalitacena.location.LOCATION_PERMISSIONS
import cz.kvalitacena.location.LocationResult
import cz.kvalitacena.location.getCurrentLocation
import cz.kvalitacena.location.hasAnyLocationPermission
import cz.kvalitacena.location.hasOnlyApproximateLocation
import cz.kvalitacena.ui.common.NavigationResults
import cz.kvalitacena.ui.common.ProductImagePreview
import cz.kvalitacena.ui.common.SELECTABLE_PRICE_KINDS
import cz.kvalitacena.ui.common.SELECTABLE_VARIABLE_WEIGHT_QUANTITY_BASES
import cz.kvalitacena.ui.common.formatShortDate
import cz.kvalitacena.ui.common.netContentLabel
import cz.kvalitacena.ui.common.priceKindLabel
import cz.kvalitacena.ui.common.quantityBasisLabel
import cz.kvalitacena.ui.common.productImageUrl
import cz.kvalitacena.ui.common.SingleLineTextField
import cz.kvalitacena.ui.common.StorePicker
import cz.kvalitacena.ui.settings.widerNearbyRadius
import cz.kvalitacena.ui.common.currencyForCountry
import cz.kvalitacena.ui.common.rememberMoneyFormatter
import cz.kvalitacena.ui.common.usesExternalImage
import cz.kvalitacena.ui.navigation.LocalNavigationExitGuard
import cz.kvalitacena.ui.navigation.ReportUnsavedChanges
import cz.kvalitacena.ui.theme.Spacing
import java.time.LocalDate
import java.util.Currency
import kotlinx.coroutines.launch

// Čísla a nejvýš jedna desetinná čárka nebo tečka — obojí se dál akceptuje shodně
// (PriceRowValidation.parseAmount převádí čárku na tečku před parsováním).
private val PRICE_INPUT_PATTERN = Regex("^\\d*[.,]?\\d*$")

// Jen celé číslo — počet kusů u MULTIBUY ("3 za 50").
private val QUANTITY_INPUT_PATTERN = Regex("^\\d*$")

/**
 * Obrazovky 2–4 flow "sken → cena → výběr provozovny → odeslání". Vstupem
 * je buď naskenovaný kód, nebo id produktu z detailu (tlačítko "Zapsat cenu") — viz
 * [PriceEntryTarget]. `onDone` se volá po úspěšném zápisu i po "zpět" z neznámého kódu; vede
 * vždy tam, odkud se na tuhle obrazovku přišlo (sken nebo detail).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceEntryScreen(
  target: PriceEntryTarget,
  onDone: () -> Unit,
  onAddStore: () -> Unit,
  onAddProduct: (barcode: String?) -> Unit,
  onSearchPrices: (query: String) -> Unit,
) {
  val context = LocalContext.current
  val appContext = context.applicationContext
  val viewModel: PriceEntryViewModel = viewModel(
    factory = viewModelFactory {
      initializer {
        PriceEntryViewModel(
          AppContainer.graphQlClient,
          target,
          AppContainer.countryStore,
          AppContainer.priceEntryVisibilityStore,
          AppContainer.lastStoreStore,
          AppContainer.nearbyRadiusStore,
          pendingPrices = AppContainer.pendingPrices,
          storeCache = AppContainer.storeCache,
          isOnline = { AppContainer.networkMonitor.online.value },
          currentUserUid = {
            AppContainer.authRepository.currentUserUid().takeIf { AppContainer.authRepository.isLoggedIn.value }
          },
          scheduleUpload = { PriceWork.enqueueUpload(appContext) },
        )
      }
    },
  )
  val scope = rememberCoroutineScope()
  val isLoggedIn by AppContainer.authRepository.isLoggedIn.collectAsState()
  val exitGuard = LocalNavigationExitGuard.current
  val hasUnsavedChanges = viewModel.selectedStore != null || viewModel.observedAt.isNotBlank() ||
    viewModel.quantityBasis != "PACKAGE" || viewModel.priceRows.size > 1 ||
    viewModel.priceRows.any {
      it.priceAmount.isNotBlank() || it.multibuyQty.isNotBlank() || it.multibuyTotal.isNotBlank() ||
        it.promoValidFrom.isNotBlank() || it.promoValidTo.isNotBlank() || it.priceKind != "REGULAR"
    }
  ReportUnsavedChanges(hasUnsavedChanges && !viewModel.submitSuccess)

  // Vyzvednutí výsledku z formuláře obchodu/zboží po návratu na tuhle obrazovku — viz
  // NavigationResults. LaunchedEffect(Unit) proběhne znovu pokaždé, když se sem znovu vstoupí
  // (navigation-compose composable{} obrazovku při odchodu z kompozice úplně zahodí a při
  // návratu postaví znovu, ViewModel ale přežívá ve svém NavBackStackEntry).
  LaunchedEffect(Unit) {
    NavigationResults.newStore?.let {
      viewModel.onNewStoreCreated(it)
      NavigationResults.newStore = null
    }
    NavigationResults.newProduct?.let {
      viewModel.onNewProductCreated(it, NavigationResults.productAlias)
      NavigationResults.newProduct = null
      NavigationResults.productAlias = null
    }
  }

  fun locateAndLoadNearby() {
    viewModel.startLocating()
    scope.launch {
      // Každý důvod, proč poloha není, má vlastní stav — jen tak umí obrazovka poradit, co
      // udělat, aby to příště vyšlo (docs/design.md, "Čtyři stavy každé obrazovky").
      when (val result = getCurrentLocation(context)) {
        is LocationResult.Found -> viewModel.onLocationResolved(result.lat, result.lon)
        is LocationResult.ProvidersDisabled -> viewModel.onLocationDisabled()
        is LocationResult.Timeout -> viewModel.onLocationTimeout()
      }
    }
  }

  // O obě oprávnění naráz — jinak Android nenabídne volbu "Přesná"/"Přibližná"
  // (location/LocationPermissions.kt, proč na přibližné polohu malý okruh nefunguje).
  val locationPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) { result ->
    if (result.values.any { it }) locateAndLoadNearby() else viewModel.onLocationPermissionMissing()
  }

  /**
   * Hledání v okolí. O oprávnění se appka nikdy neřekne sama od sebe — systémový dialog, který
   * vyskočí hned po otevření obrazovky, lidé odklikávají "zamítnout" a na Androidu je odmítnutí
   * lepivé. Místo toho stav [NearbyState.PermissionMissing] vysvětlí, k čemu to je, a dialog
   * vyvolá až tlačítko.
   */
  fun findNearbyStores(userAsked: Boolean) {
    if (hasAnyLocationPermission(context)) locateAndLoadNearby()
    else if (userAsked) locationPermissionLauncher.launch(LOCATION_PERMISSIONS)
    else viewModel.onLocationPermissionMissing()
  }

  // Obchody v okolí se hledají SAMY, bez mačkání tlačítka — ale až když je sekce zápisu ceny
  // rozbalená: nováčkovi je schovaná (PriceEntryVisibilityStore) a GPS fix i dotaz na server by
  // shořely naprázdno. shouldAutoLocate() pustí hledání nejvýš jednou za život ViewModelu, takže
  // ho návrat z formuláře obchodu/zboží (obrazovka se staví znovu) nespustí podruhé.
  LaunchedEffect(viewModel.priceEntryExpanded) {
    if (viewModel.priceEntryExpanded && viewModel.shouldAutoLocate()) findNearbyStores(userAsked = false)
  }

  // Po úspěšném zápisu se obrazovka rovnou opouští (návrat tam, odkud se přišlo) — hláška
  // o úspěchu by na ní jen problikla, proto potvrzujeme Toastem, který přežije i tuhle navigaci.
  // Cena uložená do fronty (bez signálu) dostane jinou větu: „zapsáno" by tvrdilo, že ji server
  // už má, a uživatel by ji pak marně hledal v grafu.
  val submitSuccessMessage = stringResource(R.string.price_entry_submit_success)
  val submitQueuedMessage = stringResource(R.string.price_entry_submit_queued)
  LaunchedEffect(viewModel.submitSuccess) {
    if (viewModel.submitSuccess) {
      if (viewModel.submitQueued) Toast.makeText(context, submitQueuedMessage, Toast.LENGTH_LONG).show()
      else Toast.makeText(context, submitSuccessMessage, Toast.LENGTH_SHORT).show()
      onDone()
    }
  }

  val online by AppContainer.networkMonitor.online.collectAsState()
  val product = viewModel.product

  Column(modifier = Modifier.fillMaxSize()) {
    when {
      viewModel.loading -> {
        Column(
          modifier = Modifier.fillMaxSize().padding(Spacing.lg),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }
      }

      // Zboží bez sítě (productPendingLookup) NENÍ „nenalezeno" — appka neví, jestli v katalogu
      // je, takže tu nesmí nabízet založení nového (vznikla by duplicita zboží, které tam dávno je).
      viewModel.notFound || (product == null && !viewModel.productPendingLookup) -> {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg)) {
          val message = when {
            viewModel.offUnavailable -> stringResource(R.string.price_entry_off_unavailable)
            target is PriceEntryTarget.ByBarcode ->
              stringResource(R.string.price_entry_code_unknown, target.barcode)
            else -> stringResource(R.string.product_not_found)
          }
          Text(message, style = MaterialTheme.typography.bodyLarge)
          Gap()
          // Dřív tu bylo jen "Zpět" — slepá ulička pro neznámý kód. Teď jde zboží rovnou založit,
          // s předvyplněným EANem (docs/reputace.md, "Zboží bez čárového kódu" — tohle je ale
          // varianta SE známým kódem, na rozdíl od bezkódové druhové položky z formuláře hledání).
          // Založení vyžaduje přihlášení (backend ProductCatalogService) — anonymovi se nabídne
          // jen "Zpět", ne formulář, který by na odeslání skončil UNAUTHORIZED.
          if (isLoggedIn) {
            Button(onClick = { onAddProduct(viewModel.barcodeForNewProduct()) }, modifier = Modifier.fillMaxWidth()) {
              Text(stringResource(R.string.price_entry_create_product))
            }
          } else {
            Text(
              stringResource(R.string.price_entry_create_product_requires_login),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Gap()
          OutlinedButton(onClick = { exitGuard.requestNavigation(onDone) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_back))
          }
        }
      }

      else -> {
        // Sekce "Zadat cenu" je schovaná za tlačítkem, dokud si ji uživatel sám nerozbalí nebo
        // dokud jednou úspěšně nezapíše cenu (PriceEntryViewModel.priceEntryExpanded) — appka
        // slouží stejně dobře lidem, co jen hledají ceny poblíž. Bez načteného zboží je rozbalená
        // vždycky (jiný smysl obrazovka bez zboží a bez sítě nemá).
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.lg)) {
          if (product != null) {
            ProductHeader(product = product, onSearchPrices = { onSearchPrices(viewModel.searchQueryForPrices()) })
          } else {
            PendingLookupNotice(
              barcode = viewModel.barcodeForNewProduct().orEmpty(),
              online = online,
              onRetry = viewModel::retryLoadProduct,
            )
          }

          if (viewModel.priceEntryExpanded) {
            Gap()
            HorizontalDivider()
            Gap()

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(stringResource(R.string.price_entry_write_price), style = MaterialTheme.typography.titleMedium)
              // Bez zboží by po sbalení zůstala prázdná obrazovka s jediným tlačítkem.
              if (product != null) {
                TextButton(onClick = { viewModel.hidePriceEntry() }) {
                  Text(stringResource(R.string.price_entry_hide))
                }
              }
            }
            Gap()

            viewModel.submitError?.let {
              Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
              Gap()
            }

            val widerRadius = widerNearbyRadius(viewModel.activeRadius)
            StorePicker(
              query = viewModel.storeQuery,
              onQueryChange = viewModel::onStoreQueryChange,
              suggestions = viewModel.storeSuggestions,
              searching = viewModel.storeSearching,
              selectedStoreId = viewModel.selectedStore?.id,
              onSelect = viewModel::onStoreSelected,
              onAddNew = onAddStore,
              nearbyState = viewModel.nearbyState,
              osmState = viewModel.osmState,
              onRequestLocationPermission = { findNearbyStores(userAsked = true) },
              onOpenLocationSettings = { openLocationSettings(context) },
              onRetryNearby = { findNearbyStores(userAsked = true) },
              onWiderRadius = widerRadius?.let { { viewModel.widenRadius() } },
              widerRadiusLabel = widerRadius?.let { stringResource(it.labelRes) },
              onSearchOsm = viewModel::loadOsmCandidates,
              onSelectOsmCandidate = { candidate ->
                NavigationResults.osmStoreCandidate = candidate.candidate
                NavigationResults.osmStoreAttribution = viewModel.osmAttribution()
                onAddStore()
              },
              // Přibližná poloha je od systému mimo až o kilometry — bez vysvětlení by prázdný
              // výsledek v okruhu 500 m vypadal jako chyba appky.
              approximateLocation = hasOnlyApproximateLocation(context),
              suggestionsFromDevice = viewModel.storeSuggestionsFromDevice,
              isLoggedIn = isLoggedIn,
              homeCountry = AppContainer.countryStore.country,
              modifier = Modifier.fillMaxWidth(),
            )
            if (viewModel.storeScopeMismatch) {
              Text(
                stringResource(R.string.price_entry_store_not_available),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
              )
            }
            Gap()

            if (product?.isVariableWeight == true) {
              QuantityBasisDropdown(
                selected = viewModel.quantityBasis,
                options = SELECTABLE_VARIABLE_WEIGHT_QUANTITY_BASES,
                onSelect = { viewModel.quantityBasis = it },
              )
              Gap()
            } else if (product == null) {
              // Appka zatím neví, jestli jde o váhové zboží — přepínač by se ptal naslepo. Cena
              // se uloží „za balení" a po dohledání se appka zeptá (PendingPriceLogic, VARIABLE_WEIGHT).
              Text(
                stringResource(R.string.price_entry_pending_basis_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Gap()
            }

            // Volitelné, prázdné = "teď" (docs/datovy-model.md, observed_at ≠ created_at) — web
            // protějšek: price-entry-form.ts observedAt/price-form.observedAtLabel.
            PromoDateField(
              value = viewModel.observedAt,
              label = stringResource(R.string.price_entry_observed_at_label),
              maxDate = LocalDate.now(),
              onChange = { date -> viewModel.observedAt = date },
              modifier = Modifier.fillMaxWidth(),
            )
            Gap()

            // Symbol podle měny vybraného obchodu (docs/lokalizace.md) — než je obchod vybraný,
            // appka měnu ještě nezná, CZK je tu jen nouzový výchozí popisek pole.
            val currencySymbol = remember(viewModel.selectedStore?.country) {
              Currency.getInstance(currencyForCountry(viewModel.selectedStore?.country)).symbol
            }

            Text(stringResource(R.string.price_entry_prices_label), style = MaterialTheme.typography.titleSmall)
            Text(
              stringResource(R.string.price_entry_prices_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap()

            viewModel.priceRows.forEach { row ->
              PriceRowFields(
                row = row,
                availableKinds = availablePriceKinds(viewModel.priceRows, row.priceKind),
                currencySymbol = currencySymbol,
                removable = viewModel.priceRows.size > 1,
                onChange = { transform -> viewModel.updatePriceRow(row.id, transform) },
                onRemove = { viewModel.removePriceRow(row.id) },
              )
              Gap()
            }

            OutlinedButton(
              onClick = { viewModel.addPriceRow() },
              enabled = viewModel.priceRows.size < SELECTABLE_PRICE_KINDS.size,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(stringResource(R.string.price_entry_add_price))
            }
            Gap()

            // Bez signálu (nebo bez dohledaného zboží) jde cena do fronty v telefonu — tlačítko
            // to řekne PŘEDEM, ať uživatele nepřekvapí, že se nic „neodeslalo". Řádek nad ním
            // radí, kde ji pak najde (PendingPricesScreen, vstup ze záložky Sken).
            val savesForLater = !online || viewModel.productPendingLookup
            if (savesForLater) {
              Text(
                stringResource(R.string.price_entry_offline_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Gap()
            }
            Button(
              onClick = { viewModel.submit() },
              enabled = viewModel.canSubmit,
              modifier = Modifier.fillMaxWidth(),
            ) {
              if (viewModel.submitting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
              } else {
                Text(stringResource(if (savesForLater) R.string.price_entry_submit_offline else R.string.price_entry_submit))
              }
            }
            Gap()

            // Odchod bez zápisu — uživatel nesmí být nucený něco vyplnit jen proto, že sem
            // omylem naskenoval kód nebo si to rozmyslel (žádný nesmyslný údaj "jen aby prošel").
            OutlinedButton(
              onClick = { exitGuard.requestNavigation(onDone) },
              enabled = !viewModel.submitting,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(stringResource(R.string.price_entry_back_without_price))
            }
          }
        }

        if (!viewModel.priceEntryExpanded) {
          HorizontalDivider()
          Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
            Button(onClick = { viewModel.expandPriceEntry() }, modifier = Modifier.fillMaxWidth()) {
              Text(stringResource(R.string.price_entry_write_price))
            }
          }
        }
      }
    }
  }
}

/** Načtené zboží: fotka, název, aktuální ceny po obchodech a cesta k hledání jeho cen. */
@Composable
private fun ProductHeader(product: Product, onSearchPrices: () -> Unit) {
  // Vizuální potvrzení, že sken trefil správné zboží — vlastní fotka, jinak obrázek
  // z Open Food Facts; bez obojího se nevykreslí nic (ProductImagePreview).
  ProductImagePreview(
    name = product.name,
    photos = product.photos,
    externalImage = product.externalImage,
    modifier = Modifier.fillMaxWidth(),
  )
  // Atribuce zdroje/licence MUSÍ být vidět — ODbL (docs/datovy-model.md).
  if (usesExternalImage(product.photos, product.externalImage)) {
    Text(
      product.externalImage!!.attribution,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
  if (productImageUrl(product.photos, product.externalImage, full = true) != null) Gap()

  Text(product.name, style = MaterialTheme.typography.headlineSmall)
  val subtitle = listOfNotNull(
    product.brand?.name,
    product.category.name,
    netContentLabel(product.netContentValue, product.netContentUom, product.isVariableWeight),
  ).joinToString(" · ")
  Text(subtitle, style = MaterialTheme.typography.bodyMedium)
  Gap()

  Text(stringResource(R.string.price_entry_current_prices), style = MaterialTheme.typography.titleMedium)
  if (product.prices.isEmpty()) {
    Text(stringResource(R.string.product_no_price_be_first), style = MaterialTheme.typography.bodyMedium)
  } else {
    Column(modifier = Modifier.padding(top = Spacing.sm)) {
      product.prices.forEach { price ->
        Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
          Text("${price.store.name} — ${priceKindLabel(price.priceKind)}")
          val moneyFormatter = rememberMoneyFormatter(price.currency)
          Text(
            stringResource(
              R.string.price_entry_price_summary,
              price.priceAmount?.let { moneyFormatter.format(it) }.orEmpty(),
              price.unitPrice?.let { moneyFormatter.format(it) }.orEmpty(),
              price.nObs,
            ),
            style = MaterialTheme.typography.bodySmall,
          )
          price.promoValidTo?.let { validTo ->
            Text(
              stringResource(R.string.product_promo_valid_until, formatShortDate(validTo)),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        HorizontalDivider()
      }
    }
  }
  Gap()

  // Výpis výš není zeměpisně omezený (jen zemí) — tlačítko přehodí do záložky Hledat
  // s naskenovaným kódem (nebo názvem, viz searchQueryForPrices), kde jde omezit na
  // město (NavigationResults.searchQuery, viz MainActivity).
  OutlinedButton(onClick = onSearchPrices, modifier = Modifier.fillMaxWidth()) {
    Text(stringResource(R.string.price_entry_search_this_code))
  }
}

/**
 * Sken bez signálu: zboží se nenačetlo kvůli SÍTI, ne proto, že by ho katalog neznal
 * (PriceEntryViewModel.productPendingLookup). Cena jde zapsat i tak — uloží se s čárovým kódem
 * do fronty a zboží dohledá `PriceUploadWorker` po připojení. Barvy `secondaryContainer` jako
 * pruh offline ([cz.kvalitacena.ui.common.OfflineBanner]): nejde o chybu.
 */
@Composable
private fun PendingLookupNotice(barcode: String, online: Boolean, onRetry: () -> Unit) {
  Surface(
    color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
      Text(stringResource(R.string.price_entry_pending_lookup_title), style = MaterialTheme.typography.titleMedium)
      Text(stringResource(R.string.pending_price_barcode, barcode), style = MaterialTheme.typography.bodyMedium)
      // Signál se mezitím vrátil — appka zboží sama nenačítá (formulář by uživateli pod rukama
      // přeskočil na jinou obrazovku), jen to řekne a nechá ho klepnout.
      Text(
        stringResource(if (online) R.string.price_entry_pending_lookup_online else R.string.price_entry_pending_lookup_text),
        style = MaterialTheme.typography.bodySmall,
      )
      TextButton(onClick = onRetry) {
        Text(stringResource(R.string.price_entry_pending_lookup_retry))
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PriceKindDropdown(selected: String, options: List<String>, onSelect: (String) -> Unit) {
  var expanded by remember { mutableStateOf(false) }

  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
    SingleLineTextField(
      value = priceKindLabel(selected),
      onValueChange = {},
      readOnly = true,
      label = stringResource(R.string.price_kind_field_label),
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { value ->
        DropdownMenuItem(
          text = { Text(priceKindLabel(value)) },
          onClick = {
            onSelect(value)
            expanded = false
          },
        )
      }
    }
  }
}

/** Jen pro váhové zboží (product.isVariableWeight) — cena na cedulce bývá za kg/l, ne za balení. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuantityBasisDropdown(selected: String, options: List<String>, onSelect: (String) -> Unit) {
  var expanded by remember { mutableStateOf(false) }

  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
    SingleLineTextField(
      value = quantityBasisLabel(selected),
      onValueChange = {},
      readOnly = true,
      label = stringResource(R.string.quantity_basis_field_label),
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { value ->
        DropdownMenuItem(
          text = { Text(quantityBasisLabel(value)) },
          onClick = {
            onSelect(value)
            expanded = false
          },
        )
      }
    }
  }
}

/** Jeden řádek "(druh ceny, částka)" — u MULTIBUY se místo částky zadává počet kusů + celková
 *  cena (server si jednotkovou cenu spočítá sám, viz PriceObservationService). */
@Composable
private fun PriceRowFields(
  row: PriceRow,
  availableKinds: List<String>,
  currencySymbol: String,
  removable: Boolean,
  onChange: ((PriceRow) -> PriceRow) -> Unit,
  onRemove: () -> Unit,
) {
  Column {
    Row(verticalAlignment = Alignment.CenterVertically) {
      androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
        PriceKindDropdown(
          selected = row.priceKind,
          options = availableKinds,
          onSelect = { kind ->
            onChange { it.copy(priceKind = kind, priceAmount = "", multibuyQty = "", multibuyTotal = "") }
          },
        )
      }
      if (removable) {
        TextButton(onClick = onRemove) {
          Text(stringResource(R.string.price_entry_remove_price))
        }
      }
    }
    Gap()

    if (row.priceKind == "MULTIBUY") {
      SingleLineTextField(
        value = row.multibuyQty,
        onValueChange = { input -> if (input.matches(QUANTITY_INPUT_PATTERN)) onChange { it.copy(multibuyQty = input) } },
        label = stringResource(R.string.price_entry_multibuy_qty_label),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
      )
      Gap()
      SingleLineTextField(
        value = row.multibuyTotal,
        onValueChange = { input -> if (input.matches(PRICE_INPUT_PATTERN)) onChange { it.copy(multibuyTotal = input) } },
        label = stringResource(R.string.price_entry_multibuy_total_label, currencySymbol),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        stringResource(R.string.price_entry_multibuy_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      SingleLineTextField(
        value = row.priceAmount,
        onValueChange = { input -> if (input.matches(PRICE_INPUT_PATTERN)) onChange { it.copy(priceAmount = input) } },
        label = stringResource(R.string.price_entry_price_label, currencySymbol),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
      )
    }

    if (row.priceKind == "PROMO") {
      Gap()
      Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        PromoDateField(
          value = row.promoValidFrom,
          label = stringResource(R.string.price_entry_promo_valid_from_label),
          // Od nesmí být v budoucnu — zapisuje se cena, kterou uživatel VIDĚL v regále.
          maxDate = LocalDate.now(),
          onChange = { date -> onChange { it.copy(promoValidFrom = date) } },
          modifier = Modifier.weight(1f),
        )
        PromoDateField(
          value = row.promoValidTo,
          label = stringResource(R.string.price_entry_promo_valid_to_label),
          maxDate = null,
          onChange = { date -> onChange { it.copy(promoValidTo = date) } },
          modifier = Modifier.weight(1f),
        )
      }
      Text(
        stringResource(R.string.price_entry_promo_validity_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** Datum jako ISO řetězec (yyyy-MM-dd) — klik na pole otevře nativní [android.app.DatePickerDialog],
 *  pole samo je jen pro zobrazení (readOnly), ať klávesnice nenabízí volný text do datumu. */
@Composable
private fun PromoDateField(
  value: String,
  label: String,
  maxDate: LocalDate?,
  onChange: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  androidx.compose.foundation.layout.Box(modifier = modifier) {
    SingleLineTextField(
      // Zobrazit lokalizovaně ("30. 8. 2026"), ne syrové ISO — stavová hodnota (onChange) dál
      // zůstává ISO yyyy-MM-dd, mění se jen to, co appka ukáže v poli.
      value = if (value.isNotBlank()) formatShortDate(value) else value,
      onValueChange = {},
      readOnly = true,
      label = label,
      modifier = Modifier.fillMaxWidth(),
    )
    androidx.compose.foundation.layout.Box(
      modifier = Modifier.matchParentSize().clickable {
        val initial = value.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
          ?: maxDate ?: LocalDate.now()
        val dialog = DatePickerDialog(
          context,
          { _, year, month, dayOfMonth -> onChange(LocalDate.of(year, month + 1, dayOfMonth).toString()) },
          initial.year,
          initial.monthValue - 1,
          initial.dayOfMonth,
        )
        if (maxDate != null) dialog.datePicker.maxDate = maxDate.atStartOfDay(java.time.ZoneId.systemDefault())
          .toInstant().toEpochMilli()
        dialog.show()
      },
    )
  }
}

@Composable
private fun Gap() {
  androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(Spacing.md))
}

/**
 * Systémové nastavení polohy — jediná cesta, jak vypnutou polohu zapnout; appka to za uživatele
 * udělat nemůže, tak ho tam aspoň dovede jedním klepnutím.
 */
private fun openLocationSettings(context: android.content.Context) {
  runCatching {
    context.startActivity(
      android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
    )
  }
}
