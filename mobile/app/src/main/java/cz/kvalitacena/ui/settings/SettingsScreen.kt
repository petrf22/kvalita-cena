package cz.kvalitacena.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.kvalitacena.BuildConfig

/**
 * Záložka "Nastavení" — placeholder podle zadání ("doplním později"), ale ne prázdný: sekce
 * Zdroje dat plní ODbL požadavek "UI vždy uvede zdroj" centrálně, ne jen u jednotlivého odkazu
 * v detailu produktu (docs/datovy-model.md).
 */
@Composable
fun SettingsScreen() {
  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    Text("Nastavení", style = MaterialTheme.typography.headlineSmall)
    Spacer()
    Text("Zatím tu nic nastavovat není.", style = MaterialTheme.typography.bodyMedium)
    Spacer()
    HorizontalDivider()
    Spacer()

    Text("Zdroje dat", style = MaterialTheme.typography.titleMedium)
    Spacer()
    Text(
      "Ceny a hodnocení kvality jsou vlastní komunitní data. Odkazy do Open Food Facts jsou " +
        "podle licence ODbL (Open Database License) — data se nikam nekopírují, appka jen " +
        "odkazuje na zdroj.",
      style = MaterialTheme.typography.bodyMedium,
    )
    Spacer()
    Text(
      "Souřadnice provozoven vycházejí z OpenStreetMap © přispěvatelé OpenStreetMap, licence ODbL.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer()
    HorizontalDivider()
    Spacer()
    Text(
      "Verze appky ${BuildConfig.VERSION_NAME}",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun Spacer() {
  androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
}
