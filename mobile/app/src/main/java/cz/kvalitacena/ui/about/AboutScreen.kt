package cz.kvalitacena.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.kvalitacena.BuildConfig
import cz.kvalitacena.R
import cz.kvalitacena.ui.common.openEmail
import cz.kvalitacena.ui.common.openUrl

/**
 * "O aplikaci" — co appka dělá, odkud bere data (dřív karta "Zdroje dat" v Nastavení, i s
 * verzí appky sem přesunuto, viz CLAUDE.md), otevřený kód a kontakt. Vzor
 * `ui/legal/LegalScreen.kt`, ale text se (na rozdíl od Podmínek/Zásad) plně překládá do všech
 * pěti jazyků — není to právní text s rizikem nepřesného strojového překladu. Webový
 * protějšek: `frontend/src/app/features/about/about-page.ts`.
 */
@Composable
fun AboutScreen(
  onDone: () -> Unit,
  onOpenFeedback: () -> Unit = {},
  onOpenTerms: () -> Unit = {},
  onOpenPrivacy: () -> Unit = {},
) {
  val context = LocalContext.current
  val githubUrl = stringResource(R.string.about_github_url)
  val contactEmail = stringResource(R.string.about_contact_email)

  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    Image(
      painter = painterResource(R.drawable.ic_logo),
      contentDescription = stringResource(R.string.app_name),
      modifier = Modifier.size(48.dp),
    )
    Spacer()
    Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineSmall)
    Spacer()

    Text(stringResource(R.string.about_what_it_is_title), style = MaterialTheme.typography.titleMedium)
    Spacer()
    Text(stringResource(R.string.about_what_it_is_text), style = MaterialTheme.typography.bodyMedium)
    Spacer()
    HorizontalDivider()
    Spacer()

    Text(stringResource(R.string.about_what_it_is_not_title), style = MaterialTheme.typography.titleMedium)
    Spacer()
    Text(stringResource(R.string.about_what_it_is_not_text), style = MaterialTheme.typography.bodyMedium)
    Spacer()
    HorizontalDivider()
    Spacer()

    Text(stringResource(R.string.about_data_sources_title), style = MaterialTheme.typography.titleMedium)
    Spacer()
    Text(stringResource(R.string.about_data_sources_text), style = MaterialTheme.typography.bodyMedium)
    Spacer()
    Text(
      stringResource(R.string.about_osm_attribution),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer()
    Text(
      stringResource(R.string.about_data_sources_other),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer()
    Text(
      stringResource(R.string.about_fx_attribution),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer()
    HorizontalDivider()
    Spacer()

    Text(stringResource(R.string.about_open_source_title), style = MaterialTheme.typography.titleMedium)
    Spacer()
    Text(stringResource(R.string.about_open_source_text), style = MaterialTheme.typography.bodyMedium)
    Spacer()
    Text(
      stringResource(R.string.about_open_source_link),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.clickable { openUrl(context, githubUrl) },
    )
    Spacer()
    HorizontalDivider()
    Spacer()

    Text(stringResource(R.string.about_author_title), style = MaterialTheme.typography.titleMedium)
    Spacer()
    Text(stringResource(R.string.about_author_text), style = MaterialTheme.typography.bodyMedium)
    Spacer()
    HorizontalDivider()
    Spacer()

    Text(stringResource(R.string.about_contact_title), style = MaterialTheme.typography.titleMedium)
    Spacer()
    Text(stringResource(R.string.about_contact_text), style = MaterialTheme.typography.bodyMedium)
    Spacer()
    Text(
      contactEmail,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.clickable { openEmail(context, contactEmail) },
    )
    Spacer()
    Text(
      stringResource(R.string.about_feedback_link),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.clickable(onClick = onOpenFeedback),
    )
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
      stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer()
    OutlinedButton(onClick = onDone) {
      Text(stringResource(R.string.common_back))
    }
  }
}

@Composable
private fun Spacer() {
  androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
}
