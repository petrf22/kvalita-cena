package cz.kvalitacena.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.kvalitacena.R
import cz.kvalitacena.ui.settings.AppLang
import cz.kvalitacena.ui.settings.currentAppLang
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Seznam změn appky — data čte z `assets/changelog.json`, generovaného `tools/version/
 * sync.mjs` z kořenového `CHANGELOG.md` (needituj JSON ručně). Text položek je záměrně jen
 * česky, stejně jako Podmínky/Zásady (`ui/legal/LegalScreen.kt`) — [R.string
 * .changelog_czech_only_notice] to appka v jiném jazyce řekne rovnou. Webový protějšek:
 * `frontend/src/app/features/changelog/changelog-page.ts`, čte tatáž generovaná data.
 */
@Serializable
private data class ChangelogItem(val text: String, val parts: List<String> = emptyList())

@Serializable
private data class ChangelogSection(val title: String, val items: List<ChangelogItem>)

@Serializable
private data class ChangelogRelease(
  val version: String,
  val date: String,
  val sections: List<ChangelogSection>,
)

private val json = Json { ignoreUnknownKeys = true }

@Composable
fun ChangelogScreen(onDone: () -> Unit) {
  val context = LocalContext.current
  val releases = remember {
    runCatching {
      context.assets.open("changelog.json").bufferedReader().use { it.readText() }
    }.mapCatching { json.decodeFromString<List<ChangelogRelease>>(it) }.getOrDefault(emptyList())
  }

  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    Text(stringResource(R.string.changelog_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))

    if (currentAppLang() != AppLang.CS) {
      Text(
        stringResource(R.string.changelog_czech_only_notice),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
      )
      Spacer(Modifier.height(16.dp))
    }

    releases.forEach { release ->
      Text(
        "${release.version} – ${release.date}",
        style = MaterialTheme.typography.titleMedium,
      )
      Spacer(Modifier.height(8.dp))
      release.sections.forEach { section ->
        Text(section.title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        section.items.forEach { item ->
          val partsSuffix = if (item.parts.isNotEmpty()) " (${item.parts.joinToString(", ")})" else ""
          Text("• ${item.text}$partsSuffix", style = MaterialTheme.typography.bodyMedium)
          Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
      }
      HorizontalDivider()
      Spacer(Modifier.height(12.dp))
    }

    OutlinedButton(onClick = onDone) {
      Text(stringResource(R.string.common_back))
    }
  }
}
