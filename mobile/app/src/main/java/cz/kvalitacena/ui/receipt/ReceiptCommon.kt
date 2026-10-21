package cz.kvalitacena.ui.receipt

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import cz.kvalitacena.R
import cz.kvalitacena.ui.theme.Spacing
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Prázdný, chybový a „nejdřív se přihlaste" stav obrazovek účtenek — jeden vzhled pro seznam,
 * detail, snímání i kontrolu (docs/design.md, „Čtyři stavy": text říká proč a co s tím).
 */
@Composable
fun ReceiptStateMessage(
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

/** Účtenky jsou soukromé a na server jdou pod účtem — anonym dostane vysvětlení a cestu k přihlášení. */
@Composable
fun ReceiptLoginRequired(onOpenAccount: () -> Unit) {
  ReceiptStateMessage(
    title = stringResource(R.string.receipts_login_title),
    text = stringResource(R.string.receipts_login_text),
    actionLabel = stringResource(R.string.receipts_login_action),
    onAction = onOpenAccount,
  )
}

/**
 * Pruh „jste offline" — neříká „chyba", ale co se stane dál (docs/design.md, „Offline": zápis
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

/** Datum a čas nákupu podle jazyka appky, v časovém pásmu telefonu. */
@Composable
fun formatReceiptDateTime(iso: String?): String? {
  if (iso == null) return null
  val locale = LocalConfiguration.current.locales[0]
  val formatter = remember(locale) {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
  }
  return runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(formatter)
  }.getOrDefault(iso)
}

/** Stav strojového zpracování účtenky pro člověka (`ReceiptProcessingStatus`). */
@Composable
fun processingStatusLabel(status: String): String = stringResource(
  when (status) {
    "QUEUED" -> R.string.receipt_status_queued
    "PROCESSING" -> R.string.receipt_status_processing
    "PROCESSED" -> R.string.receipt_status_processed
    "NOT_A_RECEIPT" -> R.string.receipt_status_not_a_receipt
    "UNREADABLE" -> R.string.receipt_status_unreadable
    else -> R.string.receipt_status_failed
  },
)

/** Co stav zpracování znamená a co s ním — pro detail účtenky, která ještě nemá řádky. */
@Composable
fun processingStatusExplanation(status: String): String = stringResource(
  when (status) {
    "QUEUED" -> R.string.receipt_status_queued_text
    "PROCESSING" -> R.string.receipt_status_processing_text
    "NOT_A_RECEIPT" -> R.string.receipt_status_not_a_receipt_text
    "UNREADABLE" -> R.string.receipt_status_unreadable_text
    else -> R.string.receipt_status_failed_text
  },
)
