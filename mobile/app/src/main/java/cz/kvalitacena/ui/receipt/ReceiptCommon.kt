package cz.kvalitacena.ui.receipt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import cz.kvalitacena.R
import cz.kvalitacena.ui.common.StateMessage
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Účtenky jsou soukromé a na server jdou pod účtem — anonym dostane vysvětlení a cestu
 * k přihlášení. Vzhled je společný stav obrazovky ([StateMessage], `ui/common/StateMessage.kt`).
 */
@Composable
fun ReceiptLoginRequired(onOpenAccount: () -> Unit) {
  StateMessage(
    title = stringResource(R.string.receipts_login_title),
    text = stringResource(R.string.receipts_login_text),
    actionLabel = stringResource(R.string.receipts_login_action),
    onAction = onOpenAccount,
  )
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
