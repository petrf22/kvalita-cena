package cz.kvalitacena.ui.product

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer
import cz.kvalitacena.network.Category
import cz.kvalitacena.ui.common.NavigationResults
import cz.kvalitacena.ui.common.SearchableDropdown
import cz.kvalitacena.ui.common.SingleLineTextField

private val UNIT_BASE_LABELS = mapOf("COUNT" to "Kus", "MASS" to "Hmotnost", "VOLUME" to "Objem")

/**
 * Založení zboží — nejdřív nabídne podobné existující položky (i bezkódové druhové, viz
 * ProductFormViewModel), teprve když se mezi nimi nic nehodí, jde založit nové. `barcode` je
 * naskenovaný kód, který se v katalogu nenašel (viz PriceEntryScreen), předvyplní pole kódu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductFormScreen(barcode: String?, onDone: () -> Unit) {
  val viewModel: ProductFormViewModel = viewModel(
    factory = viewModelFactory { initializer { ProductFormViewModel(AppContainer.graphQlClient, barcode) } },
  )

  LaunchedEffect(viewModel.created) {
    viewModel.created?.let {
      NavigationResults.newProduct = it
      onDone()
    }
  }

  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    Text("Nové zboží", style = MaterialTheme.typography.headlineSmall)
    Gap()

    SearchableDropdown(
      query = viewModel.name,
      onQueryChange = viewModel::onNameChange,
      suggestions = viewModel.suggestions,
      onSelect = { viewModel.useExisting(it) },
      itemLabel = { summary ->
        val kind = if (summary.isGeneric) " (druhová položka)" else ""
        val brand = summary.brand?.name?.let { "$it · " } ?: ""
        "${summary.name}$kind — $brand${summary.category.name}"
      },
      label = "Název zboží",
      loading = viewModel.suggestionsLoading,
      modifier = Modifier.fillMaxWidth(),
    )
    if (viewModel.suggestions.isNotEmpty()) {
      Text(
        "Není to už některá z těchhle položek? Klepnutím ji rovnou použiješ.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Gap()

    CategoryDropdown(
      categories = viewModel.categories,
      selectedId = viewModel.selectedCategoryId,
      onSelect = { viewModel.selectedCategoryId = it },
    )
    Gap()

    SingleLineTextField(
      value = viewModel.brandName,
      onValueChange = { viewModel.brandName = it },
      label = "Značka (volitelné)",
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()

    Text("Základní jednotka", style = MaterialTheme.typography.titleMedium)
    Row {
      UNIT_BASE_LABELS.forEach { (value, label) ->
        FilterChip(
          selected = viewModel.unitBase == value,
          onClick = { viewModel.unitBase = value },
          label = { Text(label) },
          modifier = Modifier.padding(end = 8.dp),
        )
      }
    }
    Gap()

    if (viewModel.unitBase != "COUNT") {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
          "Váhové zboží (cena vždy za kg/l, bez pevné gramáže)",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.weight(1f),
        )
        Switch(checked = viewModel.isVariableWeight, onCheckedChange = { viewModel.isVariableWeight = it })
      }
      Gap()

      if (!viewModel.isVariableWeight) {
        SingleLineTextField(
          value = viewModel.netContentValue,
          onValueChange = { input -> if (input.matches(Regex("^\\d*[.,]?\\d*$"))) viewModel.netContentValue = input },
          label = if (viewModel.unitBase == "MASS") "Hmotnost balení (kg)" else "Objem balení (l)",
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.fillMaxWidth(),
        )
        Gap()
      }
    }

    SingleLineTextField(
      value = viewModel.piecesInPack,
      onValueChange = { input -> if (input.all(Char::isDigit)) viewModel.piecesInPack = input },
      label = "Kusů v balení (volitelné)",
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()

    HorizontalDivider()
    Gap()

    SingleLineTextField(
      value = viewModel.code,
      onValueChange = { viewModel.code = it },
      label = "Čárový kód (volitelné)",
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth(),
    )
    Text(
      "Bez kódu vznikne druhová položka (např. „pečivo za 45 Kč“ z účtenky) — s nižší " +
        "důvěryhodností dat, ale zapsat se dá i tak.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Gap()

    viewModel.saveError?.let {
      Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      Gap()
    }

    Button(
      onClick = { viewModel.submit() },
      enabled = viewModel.name.isNotBlank() && viewModel.selectedCategoryId != null && !viewModel.saving,
      modifier = Modifier.fillMaxWidth(),
    ) {
      if (viewModel.saving) CircularProgressIndicator(modifier = Modifier.size(20.dp))
      else Text("Založit zboží")
    }
    Gap()
    OutlinedButton(onClick = onDone, enabled = !viewModel.saving, modifier = Modifier.fillMaxWidth()) {
      Text("Zpět bez založení")
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(categories: List<Category>, selectedId: String?, onSelect: (String) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  val selectedLabel = categories.find { it.id == selectedId }?.name ?: "Vyber kategorii"

  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
    SingleLineTextField(
      value = selectedLabel,
      onValueChange = {},
      readOnly = true,
      label = "Kategorie",
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      categories.forEach { category ->
        DropdownMenuItem(
          text = { Text(category.name) },
          onClick = {
            onSelect(category.id)
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
