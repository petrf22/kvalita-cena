package cz.kvalitacena.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.network.ProductSearchItem
import cz.kvalitacena.ui.common.QualityBadge
import cz.kvalitacena.ui.common.SingleLineTextField
import cz.kvalitacena.ui.common.formatRelativeDate
import cz.kvalitacena.ui.common.rememberMoneyFormatter

/**
 * Záložka "Hledat" — úvodní obrazovka appky (viz zadání). Pole s lupou nahoře, pod ním filtry
 * obchod/město a řazení, dole seznam s cenou, počtem hlášení, datem posledního hlášení a
 * značkou kvality. Chová se jako webová stránka hledání (frontend/src/app/features/search).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onProductClick: (String) -> Unit) {
  val viewModel: SearchViewModel = viewModel(
    factory = viewModelFactory { initializer { SearchViewModel(AppContainer.graphQlClient) } },
  )

  Column(modifier = Modifier.fillMaxSize()) {
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      SingleLineTextField(
        value = viewModel.query,
        onValueChange = { viewModel.query = it },
        label = stringResource(R.string.search_field_label),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
        trailingIcon = {
          IconButton(onClick = { viewModel.search() }) {
            Icon(painterResource(R.drawable.ic_tab_search), contentDescription = stringResource(R.string.search_action))
          }
        },
        modifier = Modifier.fillMaxWidth(),
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilterDropdown(
        label = stringResource(R.string.search_store_filter),
        options = viewModel.facets.stores.map { it.id to it.name },
        selected = viewModel.selectedStoreId,
        onSelect = viewModel::onStoreChange,
        modifier = Modifier.weight(1f),
      )
      FilterDropdown(
        label = stringResource(R.string.search_city_filter),
        options = viewModel.facets.cities.map { it to it },
        selected = viewModel.selectedCity,
        onSelect = viewModel::onCityChange,
        modifier = Modifier.weight(1f),
      )
    }

    SortDropdown(
      selected = viewModel.sort,
      onSelect = viewModel::onSortChange,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
    HorizontalDivider()

    when {
      viewModel.loading && viewModel.items.isEmpty() -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      }

      viewModel.errorMessage != null -> {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
          Text(viewModel.errorMessage!!.asString(), color = MaterialTheme.colorScheme.error)
        }
      }

      !viewModel.hasSearched -> {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
          Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyMedium)
        }
      }

      viewModel.items.isEmpty() -> {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
          Text(stringResource(R.string.search_not_found), style = MaterialTheme.typography.bodyMedium)
        }
      }

      else -> {
        val listState = rememberLazyListState()
        val shouldLoadMore by remember {
          derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= viewModel.items.size - 3
          }
        }
        LaunchedEffect(shouldLoadMore) {
          if (shouldLoadMore) viewModel.loadMore()
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
          items(viewModel.items, key = { it.product.id }) { item ->
            SearchResultRow(item = item, onClick = { onProductClick(item.product.id) })
            HorizontalDivider()
          }
          if (viewModel.hasMore) {
            item {
              Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SearchResultRow(item: ProductSearchItem, onClick: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(item.product.name, style = MaterialTheme.typography.titleMedium)
      if (!item.product.verified) {
        Text(
          stringResource(R.string.common_unverified),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 6.dp),
        )
      }
    }
    val subtitle = listOfNotNull(item.product.brand?.name, item.product.category.name).joinToString(" · ")
    if (subtitle.isNotBlank()) {
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column {
        val moneyFormatter = rememberMoneyFormatter(item.currency)
        val priceText = item.bestPrice?.let { moneyFormatter.format(it) } ?: stringResource(R.string.search_unknown_price)
        val confirmations = item.bestPriceObservations?.let {
          " · " + stringResource(R.string.search_confirmations, it)
        } ?: ""
        Text("$priceText$confirmations", style = MaterialTheme.typography.bodyMedium)
        Text(
          stringResource(R.string.search_report_summary, item.observationCount, formatRelativeDate(item.lastObservedAt)),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      QualityBadge(average = item.qualityAverage, count = item.qualityCount)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDropdown(
  label: String,
  options: List<Pair<String, String>>,
  selected: String?,
  onSelect: (String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  val selectedLabel = options.find { it.first == selected }?.second ?: stringResource(R.string.search_filter_all)

  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
    SingleLineTextField(
      value = selectedLabel,
      onValueChange = {},
      readOnly = true,
      label = label,
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
        text = { Text(stringResource(R.string.search_filter_all)) },
        onClick = { onSelect(null); expanded = false },
      )
      options.forEach { (value, label) ->
        DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(value); expanded = false })
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortDropdown(selected: SortOption, onSelect: (SortOption) -> Unit, modifier: Modifier = Modifier) {
  var expanded by remember { mutableStateOf(false) }

  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
    SingleLineTextField(
      value = stringResource(selected.labelRes),
      onValueChange = {},
      readOnly = true,
      label = stringResource(R.string.search_sort_label),
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      SortOption.entries.forEach { option ->
        DropdownMenuItem(
          text = { Text(stringResource(option.labelRes)) },
          onClick = { onSelect(option); expanded = false },
        )
      }
    }
  }
}
