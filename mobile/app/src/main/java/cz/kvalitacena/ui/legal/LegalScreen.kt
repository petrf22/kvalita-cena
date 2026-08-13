package cz.kvalitacena.ui.legal

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import cz.kvalitacena.R
import cz.kvalitacena.ui.settings.AppLang
import cz.kvalitacena.ui.settings.currentAppLang

/**
 * Sdílená kostra pro [TermsScreen]/[PrivacyScreen] — obě zrcadlí příslušný dokument v docs
 * (jeden zdroj pravdy pro znění), zatím vědomě jen česky (právní text, riziko nepřesného
 * strojového překladu) — [R.string.legal_czech_only_notice] to appka v jiném jazyce řekne
 * rovnou, stejný princip jako web (frontend/src/app/features/terms, .../privacy).
 */
@Composable
fun LegalScreen(
  @StringRes titleRes: Int,
  @ArrayRes headingsRes: Int,
  @ArrayRes paragraphsRes: Int,
  onDone: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    Text(stringResource(titleRes), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(4.dp))
    Text(
      stringResource(R.string.legal_effective_date),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    if (currentAppLang() != AppLang.CS) {
      Text(
        stringResource(R.string.legal_czech_only_notice),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
      )
      Spacer(Modifier.height(16.dp))
    }

    val headings = stringArrayResource(headingsRes)
    val paragraphs = stringArrayResource(paragraphsRes)
    headings.forEachIndexed { index, heading ->
      Text(heading, style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(4.dp))
      Text(paragraphs[index], style = MaterialTheme.typography.bodyMedium)
      Spacer(Modifier.height(16.dp))
    }

    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onDone) {
      Text(stringResource(R.string.common_back))
    }
  }
}
