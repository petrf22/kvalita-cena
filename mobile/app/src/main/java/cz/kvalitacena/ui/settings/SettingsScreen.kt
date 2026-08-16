package cz.kvalitacena.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.kvalitacena.AppContainer
import cz.kvalitacena.BuildConfig
import cz.kvalitacena.R
import cz.kvalitacena.ui.common.SingleLineTextField

/**
 * Záložka "Nastavení" — placeholder podle zadání ("doplním později"), ale ne prázdný: sekce
 * Zdroje dat plní ODbL požadavek "UI vždy uvede zdroj" centrálně, ne jen u jednotlivého odkazu
 * v detailu produktu (docs/datovy-model.md). Přepínač jazyka (docs/lokalizace.md) je jediné
 * místo v appce, kde jde volba jazyka nastavit ručně — mobilní protějšek webové
 * frontend/src/app/features/settings/settings-page.ts.
 *
 * Přepínač zobrazovací měny (docs/lokalizace.md, "Kurzovní lístek a zobrazovací měna") je druhá,
 * nezávislá volba — [DisplayCurrency] `null` (měna obchodu) je výchozí, appka pak vůbec
 * neposílá X-Display-Currency a nic nepřepočítává ([DisplayCurrencyStore]).
 */
@Composable
fun SettingsScreen(onOpenTerms: () -> Unit = {}, onOpenPrivacy: () -> Unit = {}) {
  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
    Spacer()

    val current = currentAppLang()
    LanguageDropdown(selected = current, onSelect = { LocaleController.setLang(it) })
    Spacer()
    HorizontalDivider()
    Spacer()

    val store = AppContainer.displayCurrencyStore
    val currentCurrency = store.currency
    CurrencyDropdown(selected = currentCurrency, onSelect = { store.select(it) })
    Spacer()
    Text(
      stringResource(R.string.settings_currency_note),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer()
    HorizontalDivider()
    Spacer()

    Text(stringResource(R.string.settings_data_sources), style = MaterialTheme.typography.titleMedium)
    Spacer()
    Text(stringResource(R.string.settings_data_sources_body), style = MaterialTheme.typography.bodyMedium)
    Spacer()
    Text(
      stringResource(R.string.settings_osm_attribution),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer()
    Text(
      stringResource(R.string.settings_fx_attribution),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer()
    HorizontalDivider()
    Spacer()

    Text(stringResource(R.string.settings_legal_title), style = MaterialTheme.typography.titleMedium)
    Spacer()
    Text(
      stringResource(R.string.settings_terms_link),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.clickable(onClick = onOpenTerms),
    )
    Spacer()
    Text(
      stringResource(R.string.settings_privacy_link),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.clickable(onClick = onOpenPrivacy),
    )
    Spacer()
    HorizontalDivider()
    Spacer()
    Text(
      stringResource(R.string.settings_app_version, BuildConfig.VERSION_NAME),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun Spacer() {
  androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
}

/**
 * Combobox místo dřívější řady `FilterChip` (jedna na jazyk) — s přibývajícími jazyky/měnami
 * (docs/lokalizace.md počítá s dalšími) by řádek chipů přetekl na malých obrazovkách. Stejná
 * `ExposedDropdownMenuBox` konvence jako `PriceKindDropdown`/`QuantityBasisDropdown`
 * (ui/price/PriceEntryScreen.kt).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(selected: AppLang, onSelect: (AppLang) -> Unit) {
  var expanded by remember { mutableStateOf(false) }

  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
    SingleLineTextField(
      value = selected.endonym,
      onValueChange = {},
      readOnly = true,
      label = stringResource(R.string.settings_language),
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      AppLang.entries.forEach { lang ->
        DropdownMenuItem(
          text = { Text(lang.endonym) },
          onClick = {
            onSelect(lang)
            expanded = false
          },
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrencyDropdown(selected: DisplayCurrency?, onSelect: (DisplayCurrency?) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  val storeDefaultLabel = stringResource(R.string.settings_currency_store_default)

  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
    SingleLineTextField(
      value = selected?.code ?: storeDefaultLabel,
      onValueChange = {},
      readOnly = true,
      label = stringResource(R.string.settings_currency),
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
        text = { Text(storeDefaultLabel) },
        onClick = {
          onSelect(null)
          expanded = false
        },
      )
      DisplayCurrency.entries.forEach { currency ->
        DropdownMenuItem(
          text = { Text(currency.code) },
          onClick = {
            onSelect(currency)
            expanded = false
          },
        )
      }
    }
  }
}
