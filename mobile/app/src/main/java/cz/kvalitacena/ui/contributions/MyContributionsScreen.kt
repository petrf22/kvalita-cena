package cz.kvalitacena.ui.contributions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
 * sama přihlášení nekontroluje (stejný vzor jako ProfileScreen — viz AccountScreen). Webový
 * protějšek: frontend features/my-contributions/my-contributions-page.ts.
 */
@Composable
fun MyContributionsScreen() {
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
      0 -> ProductsTab(viewModel)
      1 -> StoresTab(viewModel)
      2 -> ObservationsTab(viewModel)
      else -> EditsTab(viewModel)
    }
  }
}

@Composable
private fun ProductsTab(viewModel: MyContributionsViewModel) {
  val section = viewModel.products
  ContributionList(
    items = section.items,
    loading = section.loading,
    error = section.error,
    hasMore = section.hasMore,
    emptyTextRes = R.string.my_contributions_empty_products,
    onLoadMore = { viewModel.loadProducts(reset = false) },
  ) { item: MyProductItem ->
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
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
private fun StoresTab(viewModel: MyContributionsViewModel) {
  val section = viewModel.stores
  ContributionList(
    items = section.items,
    loading = section.loading,
    error = section.error,
    hasMore = section.hasMore,
    emptyTextRes = R.string.my_contributions_empty_stores,
    onLoadMore = { viewModel.loadStores(reset = false) },
  ) { item: MyStoreItem ->
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
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
private fun ObservationsTab(viewModel: MyContributionsViewModel) {
  val section = viewModel.observations
  ContributionList(
    items = section.items,
    loading = section.loading,
    error = section.error,
    hasMore = section.hasMore,
    emptyTextRes = R.string.my_contributions_empty_observations,
    onLoadMore = { viewModel.loadObservations(reset = false) },
  ) { item: MyObservationItem ->
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
      Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text("${item.product.name} — ${item.store.name} — ${priceKindLabel(item.priceKind)}")
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
private fun EditsTab(viewModel: MyContributionsViewModel) {
  val section = viewModel.edits
  ContributionList(
    items = section.items,
    loading = section.loading,
    error = section.error,
    hasMore = section.hasMore,
    emptyTextRes = R.string.my_contributions_empty_edits,
    onLoadMore = { viewModel.loadEdits(reset = false) },
  ) { item: MyEditItem ->
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
      val recordLabelRes = if (item.recordType == "PRODUCT") {
        R.string.my_contributions_record_type_product
      } else {
        R.string.my_contributions_record_type_store
      }
      val recordName = item.product?.name ?: item.store?.name ?: ""
      Text("${stringResource(recordLabelRes)}: $recordName", style = MaterialTheme.typography.titleSmall)
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
  hasMore: Boolean,
  emptyTextRes: Int,
  onLoadMore: () -> Unit,
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
      if (hasMore) {
        OutlinedButton(onClick = onLoadMore, modifier = Modifier.padding(16.dp)) {
          Text(stringResource(R.string.my_contributions_load_more))
        }
      }
    }
  }
}
