package cz.kvalitacena.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import cz.kvalitacena.ui.theme.Spacing

/**
 * Prázdný, chybový a „nejdřív se přihlas" stav celé obrazovky — jeden vzhled napříč appkou
 * (docs/design.md, „Čtyři stavy": text říká PROČ a CO S TÍM, ne jen „žádná data"). Vznikl
 * u účtenek; přesunutý sem, aby další obrazovky prázdný stav nevymýšlely znovu po svém.
 */
@Composable
fun StateMessage(
  title: String,
  text: String?,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
  secondaryLabel: String? = null,
  onSecondary: (() -> Unit)? = null,
) {
  Column(
    modifier = modifier.fillMaxSize().padding(Spacing.xxl),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
  ) {
    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    text?.let {
      Text(
        it,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
    if (actionLabel != null && onAction != null) {
      Button(onClick = onAction) { Text(actionLabel) }
    }
    if (secondaryLabel != null && onSecondary != null) {
      OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
    }
  }
}

/**
 * Pruh „jsi offline" — neříká „chyba", ale co se stane dál (docs/design.md, „Offline": zápis
 * se odešle později). Barvy z `secondaryContainer`, ne z `errorContainer`: nejde o chybu.
 */
@Composable
fun OfflineBanner(text: String, modifier: Modifier = Modifier) {
  Surface(
    color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    modifier = modifier.fillMaxWidth(),
  ) {
    Text(
      text,
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    )
  }
}
