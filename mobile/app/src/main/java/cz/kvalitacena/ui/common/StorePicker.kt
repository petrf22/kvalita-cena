package cz.kvalitacena.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.kvalitacena.R
import cz.kvalitacena.network.Store

/**
 * Výběr obchodu při zápisu ceny — kombinuje tři cesty, jak se k obchodu dostat, aby fungoval
 * i doma bez sdílené polohy nebo zpětného zápisu (docs/datovy-model.md, "Identita provozovny"):
 * napsat název/město (searchStores), stisknout "Najít v okolí" (nearbyStores, dosavadní
 * chování beze změny) nebo založit nový obchod. Nahrazuje dřívější read-only StoreDropdown
 * v PriceEntryScreen, který byl prázdný, dokud nikdo nestiskl "Najít v okolí".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorePicker(
  query: String,
  onQueryChange: (String) -> Unit,
  suggestions: List<Store>,
  searching: Boolean,
  selectedStoreId: String?,
  onSelect: (Store) -> Unit,
  onFindNearby: () -> Unit,
  locating: Boolean,
  onAddNew: () -> Unit,
  isLoggedIn: Boolean,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      SearchableDropdown(
        query = query,
        onQueryChange = onQueryChange,
        suggestions = suggestions,
        onSelect = onSelect,
        itemLabel = ::storeLabel,
        label = stringResource(R.string.store_picker_label),
        loading = searching,
        modifier = Modifier.weight(1f),
        // Založení nového obchodu vyžaduje přihlášení (docs/reputace.md, T1) — anonymovi se
        // nabídne jen napovídání a "Najít v okolí", ne slepá ulička formuláře skončící UNAUTHORIZED.
        footer = if (isLoggedIn) {
          {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.store_picker_add_new)) },
              onClick = onAddNew,
            )
          }
        } else {
          null
        },
      )
      Button(onClick = onFindNearby) {
        if (locating) CircularProgressIndicator(modifier = Modifier.size(20.dp))
        else Text(stringResource(R.string.store_picker_find_nearby))
      }
    }
    if (selectedStoreId == null && query.isBlank() && suggestions.isEmpty()) {
      val hint = if (isLoggedIn) {
        stringResource(R.string.store_picker_hint_logged_in)
      } else {
        stringResource(R.string.store_picker_hint_anonymous)
      }
      Text(
        hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}
