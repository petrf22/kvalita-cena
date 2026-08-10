package cz.kvalitacena.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.kvalitacena.BuildConfig
import cz.kvalitacena.R

/**
 * Záložka "Nastavení" — placeholder podle zadání ("doplním později"), ale ne prázdný: sekce
 * Zdroje dat plní ODbL požadavek "UI vždy uvede zdroj" centrálně, ne jen u jednotlivého odkazu
 * v detailu produktu (docs/datovy-model.md). Přepínač jazyka (docs/lokalizace.md) je jediné
 * místo v appce, kde jde volba jazyka nastavit ručně — mobilní protějšek webové
 * frontend/src/app/features/settings/settings-page.ts.
 */
@Composable
fun SettingsScreen() {
  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
    Spacer()

    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
    Spacer()
    val current = currentAppLang()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      AppLang.entries.forEach { lang ->
        FilterChip(
          selected = lang == current,
          onClick = { LocaleController.setLang(lang) },
          label = { Text(lang.endonym) },
        )
      }
    }
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
