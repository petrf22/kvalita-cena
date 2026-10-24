package cz.kvalitacena.ui.receipt.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cz.kvalitacena.R
import cz.kvalitacena.ui.theme.Spacing

private val TIPS = listOf(
  R.string.receipt_capture_tip_surface,
  R.string.receipt_capture_tip_align,
  R.string.receipt_capture_tip_wait,
  R.string.receipt_capture_tip_move,
  R.string.receipt_capture_tip_end,
)

/**
 * Návod ke snímání — sám se otevře při prvním snímání ([cz.kvalitacena.receipt.ReceiptPrefs.captureTipsSeen]),
 * pak je za tlačítkem „Jak na to". Kroky odpovídají tomu, co snímání opravdu potřebuje: kontrast
 * papíru proti podložce (detekce hran), klid na začátku (odpočet), plynulý posun dolů (překryv
 * dílů) a dojetí za spodní okraj (poslední díl se uloží až po posunu).
 */
@Composable
fun CaptureTipsDialog(onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.receipt_capture_tips_title)) },
    text = {
      Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
      ) {
        TIPS.forEachIndexed { index, tip ->
          Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
              stringResource(R.string.receipt_capture_tip_number, index + 1),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(tip), style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.receipt_capture_tips_ok)) }
    },
  )
}
