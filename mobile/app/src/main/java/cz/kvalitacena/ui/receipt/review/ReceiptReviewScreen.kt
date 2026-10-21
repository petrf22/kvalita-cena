package cz.kvalitacena.ui.receipt.review

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.receipt.DraftPart
import cz.kvalitacena.receipt.ReceiptNotifications
import cz.kvalitacena.ui.receipt.OfflineBanner
import cz.kvalitacena.ui.receipt.ReceiptLoginRequired
import cz.kvalitacena.ui.receipt.ReceiptStateMessage
import cz.kvalitacena.ui.theme.Spacing

/**
 * Kontrola účtenky před odesláním: díly jde smazat, přeskládat a nafotit znovu, citlivé údaje
 * zakrýt (výchozí návrh je spodní pás posledního dílu) a teprve pak odeslat — se souhlasem,
 * pokud ještě nebyl dán nebo se změnila jeho verze (docs/soukromi.md, „Účtenky").
 */
@Composable
fun ReceiptReviewScreen(
  onRetake: (index: Int) -> Unit,
  onAddMore: () -> Unit,
  onSubmitted: () -> Unit,
  onOpenPrivacy: () -> Unit,
  onOpenAccount: () -> Unit,
) {
  val isLoggedIn by AppContainer.authRepository.isLoggedIn.collectAsState()
  val sessionKnown by AppContainer.authRepository.sessionKnown.collectAsState()
  if (sessionKnown && !isLoggedIn) {
    ReceiptLoginRequired(onOpenAccount)
    return
  }

  val context = LocalContext.current
  val viewModel: ReceiptReviewViewModel = viewModel(
    factory = viewModelFactory {
      initializer {
        ReceiptReviewViewModel(
          context.applicationContext,
          AppContainer.receiptDraft,
          AppContainer.pendingReceipts,
          AppContainer.receiptPrefs,
          AppContainer.graphQlClient,
          AppContainer.authRepository,
        )
      }
    },
  )
  val draft by viewModel.draft.collectAsState()
  val online by AppContainer.networkMonitor.online.collectAsState()
  var deleteIndex by remember { mutableStateOf<Int?>(null) }

  LaunchedEffect(Unit) { viewModel.onShown() }

  // Notifikace o zpracování dává smysl až ve chvíli, kdy je co zpracovávat — proto se appka
  // ptá po prvním odeslání účtenky, ne při startu (Android 13+).
  val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    onSubmitted()
  }
  LaunchedEffect(viewModel.submitted) {
    if (!viewModel.submitted) return@LaunchedEffect
    val prefs = AppContainer.receiptPrefs
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      !ReceiptNotifications.canPost(context) && !prefs.notificationPermissionAsked
    ) {
      prefs.notificationPermissionAsked = true
      notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      onSubmitted()
    }
  }

  if (draft.parts.isEmpty() && !viewModel.submitted) {
    ReceiptStateMessage(
      title = stringResource(R.string.receipt_review_empty_title),
      text = stringResource(R.string.receipt_review_empty_text),
      actionLabel = stringResource(R.string.receipt_review_capture),
      onAction = onAddMore,
    )
    return
  }

  Column(Modifier.fillMaxSize()) {
    if (!online) OfflineBanner(stringResource(R.string.receipt_review_offline))
    LazyColumn(
      Modifier.weight(1f).fillMaxWidth(),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
      verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      item { SensitiveDataWarning() }
      itemsIndexed(draft.parts, key = { _, part -> part.file }) { index, part ->
        PartCard(
          part = part,
          index = index,
          count = draft.parts.size,
          onEditMasks = { viewModel.editingIndex = index },
          onMoveUp = { viewModel.moveUp(index) },
          onMoveDown = { viewModel.moveDown(index) },
          onRetake = { onRetake(index) },
          onDelete = { deleteIndex = index },
        )
      }
      item {
        OutlinedButton(onClick = onAddMore, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.receipt_review_add_more))
        }
      }
    }
    Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
      viewModel.submitError?.let {
        Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }
      Button(
        onClick = viewModel::requestSubmit,
        enabled = !viewModel.submitting && draft.parts.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
      ) {
        if (viewModel.submitting) {
          CircularProgressIndicator(Modifier.size(20.dp))
        } else {
          Text(stringResource(R.string.receipt_review_submit, draft.parts.size))
        }
      }
    }
  }

  viewModel.editingIndex?.let { index ->
    draft.parts.getOrNull(index)?.let { part ->
      MaskEditorDialog(
        file = AppContainer.receiptDraft.fileOf(part),
        imageWidth = part.width,
        imageHeight = part.height,
        initialMasks = part.masks,
        onSave = { viewModel.saveMasks(index, it) },
        onDismiss = { viewModel.editingIndex = null },
      )
    }
  }

  deleteIndex?.let { index ->
    AlertDialog(
      onDismissRequest = { deleteIndex = null },
      title = { Text(stringResource(R.string.receipt_review_delete_title)) },
      text = { Text(stringResource(R.string.receipt_review_delete_text, index + 1)) },
      confirmButton = {
        TextButton(onClick = { viewModel.remove(index); deleteIndex = null }) {
          Text(stringResource(R.string.common_delete))
        }
      },
      dismissButton = { TextButton(onClick = { deleteIndex = null }) { Text(stringResource(R.string.common_cancel)) } },
    )
  }

  if (viewModel.consentVisible) {
    ReceiptConsentDialog(
      initialArchive = AppContainer.receiptPrefs.archiveConsent,
      onAccept = viewModel::acceptConsent,
      onDismiss = viewModel::dismissConsent,
      onOpenPrivacy = onOpenPrivacy,
    )
  }
}

/** Výrazné upozornění nad díly — zakrýt se musí dřív, než se cokoli odešle. */
@Composable
private fun SensitiveDataWarning() {
  Card(
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.errorContainer,
      contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ),
  ) {
    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
      Text(stringResource(R.string.receipt_review_warning_title), style = MaterialTheme.typography.titleMedium)
      Text(stringResource(R.string.receipt_review_warning_text), style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@Composable
private fun PartCard(
  part: DraftPart,
  index: Int,
  count: Int,
  onEditMasks: () -> Unit,
  onMoveUp: () -> Unit,
  onMoveDown: () -> Unit,
  onRetake: () -> Unit,
  onDelete: () -> Unit,
) {
  Card {
    Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
      Text(
        stringResource(R.string.receipt_review_part, index + 1, count),
        style = MaterialTheme.typography.titleSmall,
      )
      val masked = part.masks.size
      Text(
        if (masked == 0) stringResource(R.string.receipt_review_no_masks)
        else stringResource(R.string.receipt_review_masks, masked),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Box(Modifier.fillMaxWidth().clickable(onClick = onEditMasks)) {
        MaskedPartImage(
          file = AppContainer.receiptDraft.fileOf(part),
          width = part.width,
          height = part.height,
          masks = part.masks,
          modifier = Modifier.fillMaxWidth().aspectRatio(part.width.toFloat() / part.height.coerceAtLeast(1)),
        )
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Button(onClick = onEditMasks) { Text(stringResource(R.string.receipt_review_mask)) }
        TextButton(onClick = onMoveUp, enabled = index > 0) { Text(stringResource(R.string.receipt_review_move_up)) }
        TextButton(onClick = onMoveDown, enabled = index < count - 1) {
          Text(stringResource(R.string.receipt_review_move_down))
        }
        TextButton(onClick = onRetake) { Text(stringResource(R.string.receipt_review_retake)) }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.common_delete)) }
      }
    }
  }
}
