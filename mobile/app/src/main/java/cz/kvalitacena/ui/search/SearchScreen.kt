package cz.kvalitacena.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.network.ProductSearchItem
import cz.kvalitacena.ui.common.NavigationResults
import cz.kvalitacena.ui.common.ProductThumb
import cz.kvalitacena.ui.common.QualityBadge
import cz.kvalitacena.ui.common.SearchableDropdown
import cz.kvalitacena.ui.common.SingleLineTextField
import cz.kvalitacena.ui.common.categoryChoicesFor
import cz.kvalitacena.ui.common.formatRelativeDate
import cz.kvalitacena.ui.common.rememberMoneyFormatter
import cz.kvalitacena.ui.common.storeLabel

/**
 * Záložka "Hledat" — úvodní obrazovka appky (viz zadání). Pole s lupou nahoře, pod ním filtry
 * obchod/město a řazení, dole seznam s cenou, počtem hlášení, datem posledního hlášení a
 * značkou kvality. Chová se jako webová stránka hledání (frontend/src/app/features/search).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onProductClick: (String) -> Unit, onAddProduct: () -> Unit = {}) {
  val viewModel: SearchViewModel = viewModel(
    factory = viewModelFactory {
      initializer {
        SearchViewModel(
          AppContainer.graphQlClient,
          AppContainer.authRepository,
          AppContainer.countryStore,
          AppContainer.searchFilterStore,
        )
      }
    },
  )

  // Vyzvednutí kódu/názvu z PriceEntryScreen ("Hledat ceny tohoto zboží") — stejný vzor jako
  // NavigationResults.newStore/newProduct v PriceEntryScreen.
  LaunchedEffect(Unit) {
    NavigationResults.searchQuery?.let {
      viewModel.applyExternalQuery(it)
      NavigationResults.searchQuery = null
    }
  }

  val accessToken by AppContainer.authRepository.accessToken.collectAsState()
  val isLoggedIn = accessToken != null
  var filtersExpanded by rememberSaveable { mutableStateOf(false) }

  Column(modifier = Modifier.fillMaxSize()) {
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      SingleLineTextField(
        value = viewModel.query,
        onValueChange = { viewModel.query = it },
        label = stringResource(R.string.search_field_label),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
          filtersExpanded = false
          viewModel.search()
        }),
        trailingIcon = {
          IconButton(onClick = {
            filtersExpanded = false
            viewModel.search()
          }) {
            Icon(painterResource(R.drawable.ic_tab_search), contentDescription = stringResource(R.string.search_action))
          }
        },
        modifier = Modifier.fillMaxWidth(),
      )
    }

    val activeFilterCount = listOf(
      viewModel.selectedStoreId,
      viewModel.selectedCity,
      viewModel.selectedCategoryId,
    ).count { it != null }
    OutlinedButton(
      onClick = { filtersExpanded = !filtersExpanded },
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
      Text(
        if (activeFilterCount == 0) {
          stringResource(R.string.search_filters)
        } else {
          stringResource(R.string.search_filters_active, activeFilterCount)
        },
        modifier = Modifier.weight(1f),
        textAlign = TextAlign.Start,
      )
      ExposedDropdownMenuDefaults.TrailingIcon(expanded = filtersExpanded)
    }

    val locale = LocalConfiguration.current.locales[0]
    val categoryChoices = remember(viewModel.categories, viewModel.categoryQuery, locale) {
      categoryChoicesFor(viewModel.categoryQuery, viewModel.categories, locale)
    }
    AnimatedVisibility(visible = filtersExpanded) {
      Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilterDropdown(
            label = stringResource(R.string.search_store_filter),
            options = viewModel.facets.stores.map { it.id to storeLabel(it, AppContainer.countryStore.country) },
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

        // Breadcrumb vybrané kategorie potřebuje vlastní šířku, ne půlku řádku.
        SearchableDropdown(
          query = viewModel.categoryQuery,
          onQueryChange = viewModel::onCategoryQueryChange,
          suggestions = categoryChoices,
          onSelect = viewModel::onCategorySelected,
          itemLabel = { it.label },
          label = stringResource(R.string.search_category_filter),
          footer = {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.search_filter_all)) },
              onClick = { viewModel.clearCategory() },
            )
          },
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        SortDropdown(
          selected = viewModel.sort,
          onSelect = viewModel::onSortChange,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
      }
    }
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
        Column(
          modifier = Modifier.fillMaxSize().padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          Text(
            stringResource(R.string.search_not_found),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
          )
          Spacer(Modifier.height(16.dp))
          // Bezkódová druhová položka (docs/reputace.md, "Zboží bez čárového kódu") — appka po
          // založení rovnou nabídne zápis ceny, viz MainActivity (writePrice=true), stejná
          // parita jako web price-entry-page.html (searchMode 'name', addNewButton).
          if (isLoggedIn) {
            Text(
              stringResource(R.string.search_add_new_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onAddProduct) {
              Text(stringResource(R.string.search_add_new_button))
            }
          } else {
            Text(
              stringResource(R.string.search_add_new_requires_login),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )
          }
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

        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(vertical = 8.dp),
        ) {
          items(viewModel.items, key = { it.product.id }) { item ->
            SearchResultCard(item = item, onClick = { onProductClick(item.product.id) })
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
private fun SearchResultCard(item: ProductSearchItem, onClick: () -> Unit) {
  Card(
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 6.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.Top) {
        ProductThumb(
          name = item.product.name,
          photos = item.product.photos,
          externalImage = item.product.externalImage,
          size = 72.dp,
          modifier = Modifier.padding(end = 14.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
          Text(
            item.product.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          val subtitle = listOfNotNull(item.product.brand?.name, item.product.category.name).joinToString(" · ")
          if (subtitle.isNotBlank()) {
            Text(
              subtitle,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(top = 2.dp),
            )
          }
          if (!item.product.verified) {
            Text(
              stringResource(R.string.common_unverified),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.tertiary,
              modifier = Modifier.padding(top = 6.dp),
            )
          }
        }
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

      // Přepočtená hodnota (X-Display-Currency), když je — jinak originál v měně obchodu
      // (docs/lokalizace.md, "Kurzovní lístek a zobrazovací měna"). Cena je hlavní údaj
      // výsledku, proto dostává vlastní řádek a nesoutěží o šířku s dlouhým štítkem kvality.
      val displayAmount = item.converted?.amount ?: item.bestPrice
      val moneyFormatter = rememberMoneyFormatter(item.converted?.currency ?: item.currency)
      val priceText = displayAmount?.let { moneyFormatter.format(it) } ?: stringResource(R.string.search_unknown_price)
      Text(
        stringResource(R.string.product_cheapest_title),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          priceText,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          color = if (displayAmount == null) {
            MaterialTheme.colorScheme.onSurfaceVariant
          } else {
            MaterialTheme.colorScheme.primary
          },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        item.bestPriceObservations?.let {
          Text(
            stringResource(R.string.search_confirmations, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
          )
        }
      }

      item.cheapestStore?.let { store ->
        Text(
          storeLabel(store, AppContainer.countryStore.country),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 2.dp),
        )
      }

      QualityBadge(
        average = item.qualityAverage,
        count = item.qualityCount,
        modifier = Modifier.padding(top = 12.dp),
      )
      Text(
        stringResource(R.string.search_report_summary, item.observationCount, formatRelativeDate(item.lastObservedAt)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
      )
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
