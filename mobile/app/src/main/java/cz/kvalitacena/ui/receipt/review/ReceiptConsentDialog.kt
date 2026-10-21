package cz.kvalitacena.ui.receipt.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import cz.kvalitacena.R
import cz.kvalitacena.ui.theme.Spacing

/**
 * Souhlas před prvním odesláním účtenky a znovu po zvýšení verze textu (docs/soukromi.md,
 * „Účtenky"). Zpracování provozovatelem na jeho počítači je povinné — bez něj appka nic
 * neodešle; ponechání obrázků pro zlepšení rozpoznávání je zvlášť a nepovinné.
 */
@Composable
fun ReceiptConsentDialog(
  initialArchive: Boolean,
  onAccept: (archive: Boolean) -> Unit,
  onDismiss: () -> Unit,
  onOpenPrivacy: () -> Unit,
) {
  var processing by rememberSaveable { mutableStateOf(false) }
  var archive by rememberSaveable { mutableStateOf(initialArchive) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.receipt_consent_title)) },
    text = {
      Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
      ) {
        Text(stringResource(R.string.receipt_consent_text), style = MaterialTheme.typography.bodyMedium)
        ConsentCheckbox(
          checked = processing,
          onChange = { processing = it },
          label = stringResource(R.string.receipt_consent_processing),
        )
        ConsentCheckbox(
          checked = archive,
          onChange = { archive = it },
          label = stringResource(R.string.receipt_consent_archive),
        )
        TextButton(onClick = onOpenPrivacy) {
          Text(stringResource(R.string.receipt_consent_privacy_link))
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onAccept(archive) }, enabled = processing) {
        Text(stringResource(R.string.receipt_consent_accept))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
    },
  )
}

@Composable
private fun ConsentCheckbox(checked: Boolean, onChange: (Boolean) -> Unit, label: String) {
  Row(
    Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(checked = checked, onCheckedChange = null)
    Text(label, style = MaterialTheme.typography.bodyMedium)
  }
}
