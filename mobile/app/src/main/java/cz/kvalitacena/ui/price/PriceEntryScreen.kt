package cz.kvalitacena.ui.price

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.location.getCurrentLocation
import cz.kvalitacena.ui.common.NavigationResults
import cz.kvalitacena.ui.common.SELECTABLE_PRICE_KINDS
import cz.kvalitacena.ui.common.formatShortDate
import cz.kvalitacena.ui.common.priceKindLabel
import cz.kvalitacena.ui.common.SingleLineTextField
import cz.kvalitacena.ui.common.StorePicker
import cz.kvalitacena.ui.common.currencyForCountry
import cz.kvalitacena.ui.common.rememberMoneyFormatter
import java.time.LocalDate
import java.util.Currency
import kotlinx.coroutines.launch

// Čísla a nejvýš jedna desetinná čárka nebo tečka — obojí se dál akceptuje shodně
// (PriceRowValidation.parseAmount převádí čárku na tečku před parsováním).
private val PRICE_INPUT_PATTERN = Regex("^\\d*[.,]?\\d*$")

// Jen celé číslo — počet kusů u MULTIBUY ("3 za 50").
private val QUANTITY_INPUT_PATTERN = Regex("^\\d*$")

private val QUANTITY_BASIS_LABEL_RES = mapOf(
  "PACKAGE" to R.string.quantity_basis_package,
  "PER_KG" to R.string.quantity_basis_per_kg,
  "PER_L" to R.string.quantity_basis_per_l,
  "PER_PIECE" to R.string.quantity_basis_per_piece,
)

/** Nabídka pro váhové zboží (isVariableWeight) — bez PACKAGE, ten je výchozí jen pro ostatní
 *  zboží a u váhového nedává smysl (cena na cedulce je vždy za kg/l/kus). Web: shared/enum-labels.ts. */
private val SELECTABLE_VARIABLE_WEIGHT_QUANTITY_BASES = listOf("PER_KG", "PER_L", "PER_PIECE")

/**
 * Obrazovky 2–4 flow "sken → cena → výběr provozovny → odeslání" (viz plán projektu). Vstupem
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
) {
  val viewModel: PriceEntryViewModel = viewModel(
    factory = viewModelFactory {
      initializer { PriceEntryViewModel(AppContainer.graphQlClient, target, AppContainer.countryStore) }
    },
  )
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val accessToken by AppContainer.authRepository.accessToken.collectAsState()
  val isLoggedIn = accessToken != null

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
      viewModel.onNewProductCreated(it)
      NavigationResults.newProduct = null
    }
  }

  val locationPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) {
      viewModel.startLocating()
      scope.launch {
        val location = getCurrentLocation(context)
        if (location != null) viewModel.onLocationResolved(location.latitude, location.longitude)
        else viewModel.onLocationUnavailable()
      }
    } else {
      viewModel.onLocationUnavailable()
    }
  }

  fun findNearbyStores() {
    val hasPermission = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    if (!hasPermission) {
      locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
      return
    }
    viewModel.startLocating()
    scope.launch {
      val location = getCurrentLocation(context)
      if (location != null) viewModel.onLocationResolved(location.latitude, location.longitude)
      else viewModel.onLocationUnavailable()
    }
  }

  // Po úspěšném zápisu se obrazovka rovnou opouští (návrat tam, odkud se přišlo) — hláška
  // o úspěchu by na ní jen problikla, proto potvrzujeme Toastem, který přežije i tuhle navigaci.
  val submitSuccessMessage = stringResource(R.string.price_entry_submit_success)
  LaunchedEffect(viewModel.submitSuccess) {
    if (viewModel.submitSuccess) {
      Toast.makeText(context, submitSuccessMessage, Toast.LENGTH_SHORT).show()
      onDone()
    }
  }

  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    when {
      viewModel.loading -> {
        Column(
          modifier = Modifier.fillMaxSize(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }
      }

      viewModel.notFound -> {
        val message = when (target) {
          is PriceEntryTarget.ByBarcode -> stringResource(R.string.price_entry_code_unknown, target.barcode)
          is PriceEntryTarget.ById -> stringResource(R.string.product_not_found)
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
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.common_back))
        }
      }

      else -> {
        val product = viewModel.product!!
        Text(product.name, style = MaterialTheme.typography.headlineSmall)
        val subtitle = listOfNotNull(product.brand?.name, product.category.name).joinToString(" · ")
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        Gap()

        Text(stringResource(R.string.price_entry_current_prices), style = MaterialTheme.typography.titleMedium)
        if (product.prices.isEmpty()) {
          Text(stringResource(R.string.product_no_price_be_first), style = MaterialTheme.typography.bodyMedium)
        } else {
          Column(modifier = Modifier.padding(top = 8.dp)) {
            product.prices.forEach { price ->
              Column(modifier = Modifier.padding(vertical = 4.dp)) {
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
        HorizontalDivider()
        Gap()

        Text(stringResource(R.string.price_entry_write_price), style = MaterialTheme.typography.titleMedium)
        Gap()

        viewModel.locationError?.let {
          Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          Gap()
        }
        viewModel.submitError?.let {
          Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          Gap()
        }

        StorePicker(
          query = viewModel.storeQuery,
          onQueryChange = viewModel::onStoreQueryChange,
          suggestions = viewModel.storeSuggestions,
          searching = viewModel.storeSearching,
          selectedStoreId = viewModel.selectedStore?.id,
          onSelect = viewModel::onStoreSelected,
          onFindNearby = { findNearbyStores() },
          locating = viewModel.locating,
          onAddNew = onAddStore,
          isLoggedIn = isLoggedIn,
          homeCountry = AppContainer.countryStore.country,
          modifier = Modifier.fillMaxWidth(),
        )
        Gap()

        if (product.isVariableWeight) {
          QuantityBasisDropdown(
            selected = viewModel.quantityBasis,
            options = SELECTABLE_VARIABLE_WEIGHT_QUANTITY_BASES,
            onSelect = { viewModel.quantityBasis = it },
          )
          Gap()
        }

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

        Button(
          onClick = { viewModel.submit() },
          enabled = viewModel.canSubmit,
          modifier = Modifier.fillMaxWidth(),
        ) {
          if (viewModel.submitting) CircularProgressIndicator(modifier = Modifier.size(20.dp))
          else Text(stringResource(R.string.price_entry_submit))
        }
        Gap()

        // Odchod bez zápisu — uživatel nesmí být nucený něco vyplnit jen proto, že sem
        // omylem naskenoval kód nebo si to rozmyslel (žádný nesmyslný údaj "jen aby prošel").
        OutlinedButton(
          onClick = onDone,
          enabled = !viewModel.submitting,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.price_entry_back_without_price))
        }
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
      value = QUANTITY_BASIS_LABEL_RES[selected]?.let { stringResource(it) } ?: selected,
      onValueChange = {},
      readOnly = true,
      label = stringResource(R.string.quantity_basis_field_label),
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { value ->
        DropdownMenuItem(
          text = { Text(QUANTITY_BASIS_LABEL_RES[value]?.let { stringResource(it) } ?: value) },
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
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
      value = value,
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
  androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
}
