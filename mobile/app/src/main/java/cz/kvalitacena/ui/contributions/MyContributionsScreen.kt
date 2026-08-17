package cz.kvalitacena.ui.contributions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.network.MyEditItem
import cz.kvalitacena.network.MyObservationItem
import cz.kvalitacena.network.MyProductItem
import cz.kvalitacena.network.MyStoreItem
import cz.kvalitacena.network.PublicationStatus
import cz.kvalitacena.ui.common.formatRelativeDate
import cz.kvalitacena.ui.common.priceKindLabel
import cz.kvalitacena.ui.common.rememberMoneyFormatter
import kotlin.math.ceil
import kotlin.math.max

private val TAB_LABELS = listOf(
  R.string.my_contributions_tab_products,
  R.string.my_contributions_tab_stores,
  R.string.my_contributions_tab_observations,
  R.string.my_contributions_tab_edits,
)

private val FIELD_LABELS: Map<String, Int> = mapOf(
  "name" to R.string.my_contributions_field_name,
  "brand" to R.string.my_contributions_field_brand,
  "category" to R.string.my_contributions_field_category,
  "unitBase" to R.string.my_contributions_field_unit_base,
  "netContentValue" to R.string.my_contributions_field_net_content_value,
  "netContentUom" to R.string.my_contributions_field_net_content_uom,
  "netContentBase" to R.string.my_contributions_field_net_content_base,
  "piecesInPack" to R.string.my_contributions_field_pieces_in_pack,
  "isVariableWeight" to R.string.my_contributions_field_is_variable_weight,
  "chain" to R.string.my_contributions_field_chain,
  "street" to R.string.my_contributions_field_street,
  "city" to R.string.my_contributions_field_city,
  "postalCode" to R.string.my_contributions_field_postal_code,
  "ico" to R.string.my_contributions_field_ico,
  "lat" to R.string.my_contributions_field_lat,
  "lon" to R.string.my_contributions_field_lon,
  "geoSource" to R.string.my_contributions_field_geo_source,
  "osmRef" to R.string.my_contributions_field_osm_ref,
)

/**
 * "Moje příspěvky" (docs/datovy-model.md, "Uživatelská vrstva nad globálními daty"; prahy
 * v docs/reputace.md) — jádro appky ověřitelné end-to-end: co jsem zadal/a a KDY se to
 * propaguje ostatním, se skutečnými čísly, ne jen štítkem. Dostupné jen přihlášeným, obrazovka
 * sama přihlášení nekontroluje (stejný vzor jako ProfileScreen — viz AccountScreen). Prokliky na
 * detail zboží/obchodu jdou přes `NavHost` (`MainActivity.kt`) — návrat zpátky sem (i se
 * zachovaným stavem stránkování/záložky) tak appka dostane zadarmo z back stacku Compose
 * Navigation, žádný vlastní "zpět" mechanismus navíc netřeba (na rozdíl od webu, kde SPA routing
 * stav při navigaci pryč zahazuje — `NavigationHistoryService`). Webový protějšek:
 * frontend features/my-contributions/my-contributions-page.ts.
 */
@Composable
fun MyContributionsScreen(onProductClick: (String) -> Unit, onStoreClick: (String) -> Unit) {
  val viewModel: MyContributionsViewModel = viewModel(
    factory = viewModelFactory { initializer { MyContributionsViewModel(AppContainer.graphQlClient) } },
  )
  var selectedTab by rememberSaveable { mutableIntStateOf(0) }

  Column(modifier = Modifier.fillMaxSize()) {
    Text(
      stringResource(R.string.my_contributions_title),
      style = MaterialTheme.typography.headlineSmall,
      modifier = Modifier.padding(16.dp),
    )

    TabRow(selectedTabIndex = selectedTab) {
      TAB_LABELS.forEachIndexed { index, labelRes ->
        Tab(
          selected = selectedTab == index,
          onClick = { selectedTab = index },
          text = { Text(stringResource(labelRes)) },
        )
      }
    }

    when (selectedTab) {
      0 -> ProductsTab(viewModel, onProductClick)
      1 -> StoresTab(viewModel, onStoreClick)
      2 -> ObservationsTab(viewModel, onProductClick)
      else -> EditsTab(viewModel, onProductClick, onStoreClick)
    }
  }
}

@Composable
private fun ProductsTab(viewModel: MyContributionsViewModel, onProductClick: (String) -> Unit) {
  val section = viewModel.products
  ContributionList(
    items = section.items,
    loading = section.loading,
    error = section.error,
    totalCount = section.totalCount,
    pageIndex = section.pageIndex,
    pageSize = section.pageSize,
    emptyTextRes = R.string.my_contributions_empty_products,
    onPageChange = { viewModel.changeProductsPage(it) },
    onPageSizeChange = { viewModel.changeProductsPageSize(it) },
  ) { item: MyProductItem ->
    Column(
      modifier = Modifier.fillMaxWidth()
        .clickable { onProductClick(item.product.id) }
        .padding(vertical = 8.dp),
    ) {
      Text(item.product.name, style = MaterialTheme.typography.titleSmall)
      Text(
        "${stringResource(R.string.my_contributions_created_at_label)} ${formatRelativeDate(item.createdAt)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      PublicationStatusRow(item.publication, PublicationRecordKind.PRODUCT)
    }
  }
}

@Composable
private fun StoresTab(viewModel: MyContributionsViewModel, onStoreClick: (String) -> Unit) {
  val section = viewModel.stores
  ContributionList(
    items = section.items,
    loading = section.loading,
    error = section.error,
    totalCount = section.totalCount,
    pageIndex = section.pageIndex,
    pageSize = section.pageSize,
    emptyTextRes = R.string.my_contributions_empty_stores,
    onPageChange = { viewModel.changeStoresPage(it) },
    onPageSizeChange = { viewModel.changeStoresPageSize(it) },
  ) { item: MyStoreItem ->
    Column(
      modifier = Modifier.fillMaxWidth()
        .clickable { onStoreClick(item.store.id) }
        .padding(vertical = 8.dp),
    ) {
      Text("${item.store.name} — ${item.store.city}", style = MaterialTheme.typography.titleSmall)
      Text(
        "${stringResource(R.string.my_contributions_created_at_label)} ${formatRelativeDate(item.createdAt)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      PublicationStatusRow(item.publication, PublicationRecordKind.STORE)
    }
  }
}

@Composable
private fun ObservationsTab(viewModel: MyContributionsViewModel, onProductClick: (String) -> Unit) {
  val section = viewModel.observations
  ContributionList(
    items = section.items,
    loading = section.loading,
    error = section.error,
    totalCount = section.totalCount,
    pageIndex = section.pageIndex,
    pageSize = section.pageSize,
    emptyTextRes = R.string.my_contributions_empty_observations,
    onPageChange = { viewModel.changeObservationsPage(it) },
    onPageSizeChange = { viewModel.changeObservationsPageSize(it) },
  ) { item: MyObservationItem ->
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
      Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        // Jen produkt je proklik (stejně jako na webu) — cena patří k dvojici produkt+obchod,
        // samotný obchod tu proklik nemá.
        Text(
          "${item.product.name} — ${item.store.name} — ${priceKindLabel(item.priceKind)}",
          modifier = Modifier.clickable { onProductClick(item.product.id) },
        )
        Text(
          rememberMoneyFormatter(item.converted?.currency ?: item.currency)
            .format(item.converted?.amount ?: item.priceAmount),
        )
      }
      Text(
        formatRelativeDate(item.observedAt),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      PublicationStatusRow(item.publication, PublicationRecordKind.OBSERVATION)
    }
  }
}

@Composable
private fun EditsTab(
  viewModel: MyContributionsViewModel,
  onProductClick: (String) -> Unit,
  onStoreClick: (String) -> Unit,
) {
  val section = viewModel.edits
  ContributionList(
    items = section.items,
    loading = section.loading,
    error = section.error,
    totalCount = section.totalCount,
    pageIndex = section.pageIndex,
    pageSize = section.pageSize,
    emptyTextRes = R.string.my_contributions_empty_edits,
    onPageChange = { viewModel.changeEditsPage(it) },
    onPageSizeChange = { viewModel.changeEditsPageSize(it) },
  ) { item: MyEditItem ->
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
      val recordLabelRes = if (item.recordType == "PRODUCT") {
        R.string.my_contributions_record_type_product
      } else {
        R.string.my_contributions_record_type_store
      }
      val recordName = item.product?.name ?: item.store?.name ?: ""
      Text(
        "${stringResource(recordLabelRes)}: $recordName",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.clickable {
          item.product?.let { onProductClick(it.id) } ?: item.store?.let { onStoreClick(it.id) }
        },
      )
      Text(
        "${stringResource(R.string.my_contributions_updated_at_label)} ${formatRelativeDate(item.updatedAt)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      // .map { }.joinToString(...) místo joinToString(transform = ...) — ten druhý má nullable
      // typ parametru transform, takže ho Compose compiler nepovažuje za zaručeně inline a
      // stringResource() uvnitř by spadlo na "@Composable invocations can only happen ...".
      val fieldNames = item.changedFields
        .map { field -> FIELD_LABELS[field]?.let { stringResource(it) } ?: field }
        .joinToString(", ")
      Text(
        "${stringResource(R.string.my_contributions_changed_fields_label)} $fieldNames",
        style = MaterialTheme.typography.bodySmall,
      )
      PublicationStatusRow(item.publication, PublicationRecordKind.EDIT)
    }
  }
}

@Composable
private fun PublicationStatusRow(status: PublicationStatus, kind: PublicationRecordKind) {
  Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    AssistChip(onClick = {}, label = { Text(stringResource(publicationStateLabel(status.state))) })
  }
  Text(
    publicationStatusText(status, kind).asString(),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 2.dp),
  )
}

@Composable
private fun <T> ContributionList(
  items: List<T>,
  loading: Boolean,
  error: cz.kvalitacena.ui.common.UiText?,
  totalCount: Int,
  pageIndex: Int,
  pageSize: Int,
  emptyTextRes: Int,
  onPageChange: (Int) -> Unit,
  onPageSizeChange: (Int) -> Unit,
  itemContent: @Composable (T) -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize()) {
    error?.let {
      Text(
        it.asString(),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(16.dp),
      )
    }
    if (loading && items.isEmpty()) {
      Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        CircularProgressIndicator()
      }
    } else if (items.isEmpty()) {
      Text(
        stringResource(emptyTextRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
      )
    } else {
      LazyColumn(modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = 16.dp)) {
        items(items) { item ->
          itemContent(item)
          HorizontalDivider()
        }
      }
      if (totalCount > 0) {
        PaginationBar(
          pageIndex = pageIndex,
          pageSize = pageSize,
          totalCount = totalCount,
          onPageChange = onPageChange,
          onPageSizeChange = onPageSizeChange,
        )
      }
    }
  }
}

/**
 * Skutečné stránkování (ne "načíst další") — velikost stránky je uživatelova volba
 * (`DEFAULT_PAGE_SIZE`/`PAGE_SIZE_OPTIONS` v `MyContributionsViewModel.kt`). Webový protějšek:
 * `nz-pagination` ve `frontend/src/app/features/my-contributions/my-contributions-page.html`.
 */
@Composable
private fun PaginationBar(
  pageIndex: Int,
  pageSize: Int,
  totalCount: Int,
  onPageChange: (Int) -> Unit,
  onPageSizeChange: (Int) -> Unit,
) {
  val pageCount = max(1, ceil(totalCount / pageSize.toFloat()).toInt())

  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = { onPageChange(pageIndex - 1) }, enabled = pageIndex > 1) {
        Text(stringResource(R.string.my_contributions_previous_page))
      }
      Text(
        stringResource(R.string.my_contributions_page_indicator, pageIndex, pageCount),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 8.dp),
      )
      TextButton(onClick = { onPageChange(pageIndex + 1) }, enabled = pageIndex < pageCount) {
        Text(stringResource(R.string.my_contributions_next_page))
      }
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        stringResource(R.string.my_contributions_page_size_label),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      var expanded by remember { mutableStateOf(false) }
      Box {
        TextButton(onClick = { expanded = true }) {
          Text(pageSize.toString())
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
          PAGE_SIZE_OPTIONS.forEach { size ->
            DropdownMenuItem(
              text = { Text(size.toString()) },
              onClick = {
                onPageSizeChange(size)
                expanded = false
              },
            )
          }
        }
      }
    }
  }
}
