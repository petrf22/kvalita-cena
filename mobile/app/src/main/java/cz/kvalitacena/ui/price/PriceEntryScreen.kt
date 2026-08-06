package cz.kvalitacena.ui.price

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer
import cz.kvalitacena.location.getCurrentLocation
import cz.kvalitacena.network.Store
import cz.kvalitacena.ui.common.SingleLineTextField
import kotlinx.coroutines.launch

// Čísla a nejvýš jedna desetinná čárka nebo tečka — obojí se dál akceptuje shodně
// (PriceEntryViewModel.submit() převádí čárku na tečku před parsováním).
private val PRICE_INPUT_PATTERN = Regex("^\\d*[.,]?\\d*$")

private val PRICE_KIND_LABELS = mapOf(
  "REGULAR" to "Běžná cena",
  "PROMO" to "Akce",
  "CLUB_CARD" to "Klubová karta",
  "CLEARANCE" to "Výprodej",
)

/**
 * Obrazovky 2–4 flow "sken → cena → výběr provozovny → odeslání" (viz plán projektu). Vstupem
 * je buď naskenovaný kód, nebo id produktu z detailu (tlačítko "Zapsat cenu") — viz
 * [PriceEntryTarget]. `onDone` se volá po úspěšném zápisu i po "zpět" z neznámého kódu; vede
 * vždy tam, odkud se na tuhle obrazovku přišlo (sken nebo detail).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceEntryScreen(target: PriceEntryTarget, onDone: () -> Unit) {
  val viewModel: PriceEntryViewModel = viewModel(
    factory = viewModelFactory {
      initializer { PriceEntryViewModel(AppContainer.graphQlClient, target) }
    },
  )
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

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
  LaunchedEffect(viewModel.submitSuccess) {
    if (viewModel.submitSuccess) {
      Toast.makeText(context, "Cena byla zapsána, díky!", Toast.LENGTH_SHORT).show()
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
          is PriceEntryTarget.ByBarcode -> "Kód ${target.barcode} zatím v katalogu neznáme."
          is PriceEntryTarget.ById -> "Tohle zboží se nepodařilo najít."
        }
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Gap()
        Button(onClick = onDone) { Text("Zpět") }
      }

      else -> {
        val product = viewModel.product!!
        Text(product.name, style = MaterialTheme.typography.headlineSmall)
        val subtitle = listOfNotNull(product.brand?.name, product.category.name).joinToString(" · ")
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        Gap()

        Text("Aktuální ceny po obchodech", style = MaterialTheme.typography.titleMedium)
        if (product.prices.isEmpty()) {
          Text("Zatím tu nikdo cenu nezadal — buď první.", style = MaterialTheme.typography.bodyMedium)
        } else {
          Column(modifier = Modifier.padding(top = 8.dp)) {
            product.prices.forEach { price ->
              Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text("${price.store.name} — ${PRICE_KIND_LABELS[price.priceKind] ?: price.priceKind}")
                Text(
                  "${price.priceAmount} Kč (${price.unitPrice} Kč/jednotka) · ${price.nObs} záznamy",
                  style = MaterialTheme.typography.bodySmall,
                )
              }
              HorizontalDivider()
            }
          }
        }

        Gap()
        HorizontalDivider()
        Gap()

        Text("Zadat cenu", style = MaterialTheme.typography.titleMedium)
        Gap()

        viewModel.locationError?.let {
          Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          Gap()
        }
        viewModel.submitError?.let {
          Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          Gap()
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
          StoreDropdown(
            stores = viewModel.nearbyStores,
            selectedStoreId = viewModel.selectedStoreId,
            onSelect = { viewModel.selectedStoreId = it },
            modifier = Modifier.weight(1f),
          )
          Button(onClick = { findNearbyStores() }) {
            if (viewModel.locating) CircularProgressIndicator(modifier = Modifier.size(20.dp))
            else Text("Najít v okolí")
          }
        }
        Gap()

        PriceKindDropdown(selected = viewModel.priceKind, onSelect = { viewModel.priceKind = it })
        Gap()

        SingleLineTextField(
          value = viewModel.priceAmount,
          onValueChange = { input ->
            if (input.matches(PRICE_INPUT_PATTERN)) viewModel.priceAmount = input
          },
          label = "Cena (Kč)",
          // Desetinná čárka i tečka se přijímají obě — viz PriceEntryViewModel.submit().
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
          modifier = Modifier.fillMaxWidth(),
        )
        Gap()

        Button(
          onClick = { viewModel.submit() },
          enabled = viewModel.selectedStoreId != null && viewModel.priceAmount.isNotBlank() && !viewModel.submitting,
          modifier = Modifier.fillMaxWidth(),
        ) {
          if (viewModel.submitting) CircularProgressIndicator(modifier = Modifier.size(20.dp))
          else Text("Zapsat cenu")
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoreDropdown(
  stores: List<Store>,
  selectedStoreId: String?,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  val selectedLabel = stores.find { it.id == selectedStoreId }?.let { "${it.name} — ${it.city}" } ?: "Vyber obchod"

  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
    SingleLineTextField(
      value = selectedLabel,
      onValueChange = {},
      readOnly = true,
      label = "Obchod",
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      stores.forEach { store ->
        DropdownMenuItem(
          text = { Text("${store.name} — ${store.city}") },
          onClick = {
            onSelect(store.id)
            expanded = false
          },
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PriceKindDropdown(selected: String, onSelect: (String) -> Unit) {
  var expanded by remember { mutableStateOf(false) }

  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
    SingleLineTextField(
      value = PRICE_KIND_LABELS[selected] ?: selected,
      onValueChange = {},
      readOnly = true,
      label = "Druh ceny",
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      PRICE_KIND_LABELS.forEach { (value, label) ->
        DropdownMenuItem(
          text = { Text(label) },
          onClick = {
            onSelect(value)
            expanded = false
          },
        )
      }
    }
  }
}

@Composable
private fun Gap() {
  androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
}
