package cz.kvalitacena.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Editovatelný `ExposedDropdownMenuBox` — na rozdíl od dosavadních čtyř read-only dropdownů
 * v appce (StoreDropdown/PriceKindDropdown v PriceEntryScreen, FilterDropdown/SortDropdown
 * v SearchScreen) tady uživatel PÍŠE a nabídka se mění podle toho, co napsal (např. při
 * hledání obchodu podle názvu). Debounce/volání serveru řeší volající (ViewModel), tahle
 * komponenta je čistě prezentační.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SearchableDropdown(
  query: String,
  onQueryChange: (String) -> Unit,
  suggestions: List<T>,
  onSelect: (T) -> Unit,
  itemLabel: (T) -> String,
  label: String,
  modifier: Modifier = Modifier,
  loading: Boolean = false,
  // Extra položky v patičce nabídky (např. "+ Přidat nový obchod").
  footer: (@Composable () -> Unit)? = null,
) {
  var expanded by remember { mutableStateOf(false) }
  val showMenu = expanded && (suggestions.isNotEmpty() || footer != null)

  ExposedDropdownMenuBox(expanded = showMenu, onExpandedChange = { expanded = it }, modifier = modifier) {
    SingleLineTextField(
      value = query,
      onValueChange = {
        onQueryChange(it)
        expanded = true
      },
      label = label,
      trailingIcon = {
        if (loading) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp))
        } else {
          ExposedDropdownMenuDefaults.TrailingIcon(expanded = showMenu)
        }
      },
      modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = showMenu, onDismissRequest = { expanded = false }) {
      suggestions.forEach { item ->
        DropdownMenuItem(
          text = { Text(itemLabel(item)) },
          onClick = {
            onSelect(item)
            expanded = false
          },
        )
      }
      footer?.invoke()
    }
  }
}
