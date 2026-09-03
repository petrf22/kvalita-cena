package cz.kvalitacena.ui.feedback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.crash.CrashReporter
import cz.kvalitacena.ui.navigation.LocalNavigationExitGuard
import cz.kvalitacena.ui.navigation.ReportUnsavedChanges

/**
 * Jediný first-party kanál zpětné vazby (core.feedback, docs/nasazeni.md "Než pozvat první
 * lidi") — funguje i BEZ přihlášení. [source] je orientační popis obrazovky, ze které se sem
 * vstoupilo (Nastavení/O aplikaci), appka na tom nic nerozhoduje (core.feedback.page_ref).
 * Webový protějšek: `frontend/src/app/features/feedback/feedback-page.ts`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(source: String, onDone: () -> Unit) {
  val context = LocalContext.current
  val viewModel: FeedbackViewModel = viewModel(
    factory = viewModelFactory {
      initializer { FeedbackViewModel(AppContainer.graphQlClient, source) }
    },
  )
  // Načteno JEDNOU při vstupu na obrazovku, ne při každé rekompozici — appka nemá jak zjistit,
  // že mezitím appka spadla znovu (proces zůstává živý), takže by se stejně jen znovu přečetl
  // ten samý soubor.
  val pendingCrashReport = remember { CrashReporter.pendingReport(context) }
  val exitGuard = LocalNavigationExitGuard.current
  val hasUnsavedChanges = !viewModel.submitSuccess && (
    viewModel.category != "BUG" || viewModel.message.isNotBlank() ||
      viewModel.contactEmail.isNotBlank() || viewModel.attachCrashReport
  )
  ReportUnsavedChanges(hasUnsavedChanges)

  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    Text(stringResource(R.string.feedback_title), style = MaterialTheme.typography.headlineSmall)
    Spacer()

    if (viewModel.submitSuccess) {
      Text(stringResource(R.string.feedback_thanks), style = MaterialTheme.typography.bodyMedium)
      Spacer()
      OutlinedButton(onClick = { viewModel.sendAnother() }) {
        Text(stringResource(R.string.feedback_send_another))
      }
      Spacer()
      OutlinedButton(onClick = { exitGuard.requestNavigation(onDone) }) {
        Text(stringResource(R.string.common_back))
      }
      return@Column
    }

    Text(
      stringResource(R.string.feedback_intro),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer()

    viewModel.submitError?.let {
      Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      Spacer()
    }

    CategoryDropdown(selected = viewModel.category, onSelect = { viewModel.category = it })
    Spacer()

    OutlinedTextField(
      value = viewModel.message,
      onValueChange = { viewModel.message = it },
      label = { Text(stringResource(R.string.feedback_message_label)) },
      placeholder = { Text(stringResource(R.string.feedback_message_placeholder)) },
      minLines = 4,
      modifier = Modifier.fillMaxWidth(),
    )
    Spacer()

    OutlinedTextField(
      value = viewModel.contactEmail,
      onValueChange = { viewModel.contactEmail = it },
      label = { Text(stringResource(R.string.feedback_contact_email_label)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Spacer()
    Text(
      stringResource(R.string.feedback_contact_email_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer()

    if (pendingCrashReport != null) {
      HorizontalDivider()
      Spacer()
      Row {
        // Výchozí nezaškrtnuto (docs/soukromi.md) — nic se nepřiloží bez výslovné akce.
        Checkbox(checked = viewModel.attachCrashReport, onCheckedChange = { viewModel.attachCrashReport = it })
        Text(
          stringResource(R.string.feedback_attach_crash_report),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(top = 12.dp),
        )
      }
      Spacer()
    }

    OutlinedButton(
      onClick = {
        viewModel.submit(pendingCrashReport)
        if (viewModel.attachCrashReport) CrashReporter.clearPendingReport(context)
      },
      enabled = viewModel.canSubmit,
    ) {
      Text(stringResource(if (viewModel.submitting) R.string.feedback_sending else R.string.feedback_send))
    }
    Spacer()
    OutlinedButton(onClick = { exitGuard.requestNavigation(onDone) }) {
      Text(stringResource(R.string.common_back))
    }
  }
}

@Composable
private fun Spacer() {
  androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(selected: String, onSelect: (String) -> Unit) {
  var expanded by remember { mutableStateOf(false) }

  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
    OutlinedTextField(
      value = feedbackCategoryLabel(selected),
      onValueChange = {},
      readOnly = true,
      label = { Text(stringResource(R.string.feedback_category_label)) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      FEEDBACK_CATEGORIES.forEach { value ->
        DropdownMenuItem(
          text = { Text(feedbackCategoryLabel(value)) },
          onClick = {
            onSelect(value)
            expanded = false
          },
        )
      }
    }
  }
}
