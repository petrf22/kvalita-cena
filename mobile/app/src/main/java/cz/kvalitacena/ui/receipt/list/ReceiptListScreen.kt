package cz.kvalitacena.ui.receipt.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkInfo
import cz.kvalitacena.AppContainer
import cz.kvalitacena.BuildConfig
import cz.kvalitacena.R
import cz.kvalitacena.network.Receipt
import cz.kvalitacena.receipt.CODE_CONSENT_REQUIRED
import cz.kvalitacena.receipt.CODE_REQUIRES_LOGIN
import cz.kvalitacena.receipt.PendingReceipt
import cz.kvalitacena.receipt.ReceiptWork
import cz.kvalitacena.ui.common.rememberMoneyFormatter
import cz.kvalitacena.ui.receipt.OfflineBanner
import cz.kvalitacena.ui.receipt.ReceiptLoginRequired
import cz.kvalitacena.ui.receipt.ReceiptStateMessage
import cz.kvalitacena.ui.receipt.formatReceiptDateTime
import cz.kvalitacena.ui.receipt.processingStatusLabel
import cz.kvalitacena.ui.receipt.review.ReceiptConsentDialog
import cz.kvalitacena.ui.theme.Spacing

/**
 * Seznam účtenek (ze záložky Účet a z notifikace): čekající na odeslání z telefonu, pak účtenky
 * na serveru s tím, kam došlo zpracování a párování. Vidí je jen vlastník (docs/soukromi.md,
 * „Účtenky").
 */
@Composable
fun ReceiptListScreen(
  onOpenReceipt: (String) -> Unit,
  onCapture: () -> Unit,
  onOpenAccount: () -> Unit,
  onOpenPrivacy: () -> Unit,
) {
  val sessionKnown by AppContainer.authRepository.sessionKnown.collectAsState()
  val isLoggedIn by AppContainer.authRepository.isLoggedIn.collectAsState()
  if (!sessionKnown) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    return
  }
  if (!isLoggedIn) {
    ReceiptLoginRequired(onOpenAccount)
    return
  }

  val context = LocalContext.current
  val viewModel: ReceiptListViewModel = viewModel(
    factory = viewModelFactory {
      initializer {
        ReceiptListViewModel(
          context.applicationContext,
          AppContainer.graphQlClient,
          AppContainer.pendingReceipts,
          AppContainer.receiptPrefs,
          AppContainer.authRepository,
        )
      }
    },
  )
  val pending by viewModel.pending.collectAsState()
  val uploadStates by viewModel.uploadStates.collectAsState()
  val online by AppContainer.networkMonitor.online.collectAsState()
  var confirmDelete by remember { mutableStateOf<Receipt?>(null) }
  var confirmDeletePending by remember { mutableStateOf<PendingReceipt?>(null) }
  var consentFor by remember { mutableStateOf<PendingReceipt?>(null) }

  LaunchedEffect(Unit) { viewModel.onShown() }
  // Po návratu signálu načíst, co offline nešlo.
  LaunchedEffect(online) {
    if (online && viewModel.error != null) viewModel.refresh()
  }

  Column(Modifier.fillMaxSize()) {
    if (!online) OfflineBanner(stringResource(R.string.receipts_offline))
    when {
      viewModel.loading && pending.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
      viewModel.error != null && pending.isEmpty() && viewModel.items.isEmpty() -> ReceiptStateMessage(
        title = stringResource(if (online) R.string.receipts_error_title else R.string.receipts_offline_title),
        text = if (online) viewModel.error?.asString() else stringResource(R.string.receipts_offline_text),
        actionLabel = stringResource(R.string.common_retry),
        onAction = { viewModel.refresh() },
        secondaryLabel = stringResource(R.string.receipts_capture),
        onSecondary = onCapture,
      )
      !viewModel.loading && viewModel.items.isEmpty() && pending.isEmpty() -> ReceiptStateMessage(
        title = stringResource(R.string.receipts_empty_title),
        text = stringResource(R.string.receipts_empty_text),
        actionLabel = stringResource(R.string.receipts_capture),
        onAction = onCapture,
      )
      else -> LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
      ) {
        item {
          Button(onClick = onCapture, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.receipts_capture))
          }
        }
        viewModel.actionError?.let { message ->
          item {
            Text(message.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          }
        }
        if (pending.isNotEmpty()) {
          item { SectionTitle(stringResource(R.string.receipts_pending_title)) }
          items(pending, key = { "pending-${it.id}" }) { item ->
            PendingRow(
              pending = item,
              state = uploadStates[item.id],
              online = online,
              onRetry = { viewModel.retryPending(item.id) },
              onConsent = { if (viewModel.canAskConsent()) consentFor = item },
              onDelete = { confirmDeletePending = item },
            )
          }
        }
        if (viewModel.items.isNotEmpty() || viewModel.error != null) {
          item { SectionTitle(stringResource(R.string.receipts_server_title)) }
        }
        viewModel.error?.let { message ->
          item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
              Text(
                if (online) message.asString() else stringResource(R.string.receipts_offline_text),
                style = MaterialTheme.typography.bodySmall,
                color = if (online) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
              )
              TextButton(onClick = { viewModel.refresh() }) { Text(stringResource(R.string.common_retry)) }
            }
          }
        }
        items(viewModel.items, key = { it.id }) { receipt ->
          ReceiptRow(receipt, onClick = { onOpenReceipt(receipt.id) }, onDelete = { confirmDelete = receipt })
        }
        if (BuildConfig.DEBUG) {
          item {
            TextButton(onClick = { ReceiptWork.checkStatusNow(context.applicationContext) }) {
              Text(stringResource(R.string.receipts_debug_check_status))
            }
          }
        }
        if (viewModel.hasMore) {
          item {
            OutlinedButton(
              onClick = viewModel::loadMore,
              enabled = !viewModel.loadingMore,
              modifier = Modifier.fillMaxWidth(),
            ) {
              if (viewModel.loadingMore) CircularProgressIndicator(Modifier.size(20.dp))
              else Text(stringResource(R.string.receipts_load_more))
            }
          }
        }
      }
    }
  }

  confirmDelete?.let { receipt ->
    AlertDialog(
      onDismissRequest = { confirmDelete = null },
      title = { Text(stringResource(R.string.receipt_delete_title)) },
      text = { Text(stringResource(R.string.receipt_delete_text)) },
      confirmButton = {
        TextButton(onClick = { viewModel.delete(receipt.id); confirmDelete = null }) {
          Text(stringResource(R.string.common_delete))
        }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.common_cancel)) } },
    )
  }
  confirmDeletePending?.let { item ->
    AlertDialog(
      onDismissRequest = { confirmDeletePending = null },
      title = { Text(stringResource(R.string.receipt_pending_delete_title)) },
      text = { Text(stringResource(R.string.receipt_pending_delete_text)) },
      confirmButton = {
        TextButton(onClick = { viewModel.deletePending(item.id); confirmDeletePending = null }) {
          Text(stringResource(R.string.common_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmDeletePending = null }) { Text(stringResource(R.string.common_cancel)) }
      },
    )
  }
  consentFor?.let { item ->
    ReceiptConsentDialog(
      initialArchive = item.archiveConsent,
      onAccept = { archive -> viewModel.acceptConsentAndRetry(item.id, archive); consentFor = null },
      onDismiss = { consentFor = null },
      onOpenPrivacy = onOpenPrivacy,
    )
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.sm))
}

@Composable
private fun PendingRow(
  pending: PendingReceipt,
  state: WorkInfo.State?,
  online: Boolean,
  onRetry: () -> Unit,
  onConsent: () -> Unit,
  onDelete: () -> Unit,
) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
      Text(
        formatReceiptDateTime(pending.capturedAt) ?: pending.capturedAt,
        style = MaterialTheme.typography.titleSmall,
      )
      Text(
        stringResource(R.string.receipt_pending_parts, pending.partCount),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      val failed = pending.errorCode != null || pending.errorMessage != null || state == WorkInfo.State.FAILED
      val status = when {
        pending.errorCode == CODE_CONSENT_REQUIRED -> stringResource(R.string.receipt_pending_needs_consent)
        pending.errorCode == CODE_REQUIRES_LOGIN -> stringResource(R.string.receipt_pending_needs_login)
        failed -> pending.errorMessage ?: stringResource(R.string.receipt_pending_failed)
        state == WorkInfo.State.RUNNING -> stringResource(R.string.receipt_pending_uploading)
        !online -> stringResource(R.string.receipt_pending_waiting_network)
        else -> stringResource(R.string.receipt_pending_queued)
      }
      Text(
        status,
        style = MaterialTheme.typography.bodyMedium,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        when {
          pending.errorCode == CODE_CONSENT_REQUIRED -> TextButton(onClick = onConsent) {
            Text(stringResource(R.string.receipt_pending_give_consent))
          }
          failed -> TextButton(onClick = onRetry) { Text(stringResource(R.string.receipt_pending_retry)) }
        }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.common_delete)) }
      }
    }
  }
}

@Composable
private fun ReceiptRow(receipt: Receipt, onClick: () -> Unit, onDelete: () -> Unit) {
  var menuOpen by remember { mutableStateOf(false) }
  val money = rememberMoneyFormatter(receipt.currency)
  Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
    Row(Modifier.padding(start = Spacing.lg, top = Spacing.md, bottom = Spacing.md), verticalAlignment = Alignment.Top) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
          Text(
            receipt.store?.name ?: receipt.storeNameRaw ?: stringResource(R.string.receipt_unknown_store),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f, fill = false),
          )
          if (receipt.unseen) Badge { Text(stringResource(R.string.receipt_new)) }
        }
        Text(
          formatReceiptDateTime(receipt.purchasedAt ?: receipt.createdAt).orEmpty(),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        receipt.totalAmount?.let { Text(money.format(it), style = MaterialTheme.typography.bodyMedium) }
        Text(
          receiptProgressLabel(receipt),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      Box {
        IconButton(onClick = { menuOpen = true }) {
          Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.receipt_actions))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
          DropdownMenuItem(
            text = { Text(stringResource(R.string.common_delete)) },
            onClick = { menuOpen = false; onDelete() },
          )
        }
      }
    }
  }
}

/** Kam došla účtenka: strojové zpracování, pak párování uživatelem. */
@Composable
private fun receiptProgressLabel(receipt: Receipt): String {
  if (receipt.processingStatus != "PROCESSED") return processingStatusLabel(receipt.processingStatus)
  val counts = receipt.lineCounts
  return when (receipt.reviewStatus) {
    "DONE" -> stringResource(R.string.receipt_review_status_done, counts.matched, counts.total)
    "IN_PROGRESS" -> stringResource(R.string.receipt_review_status_in_progress, counts.matched + counts.ignored, counts.total)
    else -> stringResource(R.string.receipt_review_status_pending, counts.total)
  }
}
