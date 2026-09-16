package cz.kvalitacena.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cz.kvalitacena.R
import cz.kvalitacena.network.Store
import cz.kvalitacena.ui.theme.Spacing

/** Výsledky jsou přímo ve formuláři, včetně cesty z prázdného seznamu. */
@Composable
fun StorePicker(
  query: String,
  onQueryChange: (String) -> Unit,
  suggestions: List<Store>,
  searching: Boolean,
  selectedStoreId: String?,
  onSelect: (Store) -> Unit,
  onFindNearby: (() -> Unit)? = null,
  locating: Boolean = false,
  onAddNew: () -> Unit,
  isLoggedIn: Boolean,
  homeCountry: String?,
  modifier: Modifier = Modifier,
  searchCompleted: Boolean = false,
  nearbyResults: Boolean = false,
  radiusMeters: Int = 500,
) {
  Column(modifier = modifier) {
    SingleLineTextField(
      value = query,
      onValueChange = onQueryChange,
      label = stringResource(R.string.store_picker_search_label),
      supportingText = { Text(stringResource(R.string.store_picker_search_hint)) },
      modifier = Modifier.fillMaxWidth(),
    )
    if (locating || searching) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.sm)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(
          stringResource(if (locating) R.string.nearby_loading else R.string.store_picker_searching),
          modifier = Modifier.padding(start = Spacing.sm),
        )
      }
    }
    if (selectedStoreId != null) {
      Text(stringResource(R.string.store_picker_selected), color = MaterialTheme.colorScheme.primary)
    }
    if (nearbyResults && !locating && !searching) {
      Text(stringResource(R.string.nearby_results_title, radiusMeters), style = MaterialTheme.typography.titleSmall)
    }
    if (selectedStoreId == null && searchCompleted && suggestions.isEmpty() && !locating && !searching) {
      Text(
        stringResource(if (nearbyResults) R.string.nearby_empty_help else R.string.store_picker_empty_help),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = Spacing.sm),
      )
    }
    suggestions.forEach { store ->
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
          .selectable(selected = selectedStoreId == store.id, role = Role.RadioButton, onClick = { onSelect(store) })
          .padding(vertical = Spacing.xs),
      ) {
        RadioButton(selected = selectedStoreId == store.id, onClick = null)
        Text(storeLabel(store, homeCountry), modifier = Modifier.padding(start = Spacing.sm))
      }
    }
    if (onFindNearby != null) {
      OutlinedButton(onClick = onFindNearby, enabled = !locating, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.nearby_refresh, radiusMeters))
      }
    }
    if (isLoggedIn) {
      OutlinedButton(onClick = onAddNew, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.store_picker_find_or_add))
      }
    } else if (selectedStoreId == null && searchCompleted && suggestions.isEmpty()) {
      Text(stringResource(R.string.store_picker_login_to_add), style = MaterialTheme.typography.bodySmall)
    }
    if (suggestions.isNotEmpty()) {
      StoreMap(
        stores = suggestions,
        selectedStoreId = selectedStoreId,
        onSelect = onSelect,
        homeCountry = homeCountry,
        modifier = Modifier.padding(top = Spacing.sm),
      )
    }
  }
}
