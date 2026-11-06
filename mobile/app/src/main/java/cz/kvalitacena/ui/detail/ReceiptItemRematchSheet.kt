package cz.kvalitacena.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.kvalitacena.R
import cz.kvalitacena.network.ProductSummary
import cz.kvalitacena.ui.common.ProductThumb
import cz.kvalitacena.ui.common.ReceiptItemBadge
import cz.kvalitacena.ui.common.ReceiptItemChip
import cz.kvalitacena.ui.common.isReceiptItem
import cz.kvalitacena.ui.common.rememberNetContentLabeler
import cz.kvalitacena.ui.theme.Spacing

/**
 * Miniatura v nabídce cílového zboží — rozměr komponenty, ne rozestup; stejný jako miniatura
 * zboží v panelu řádku účtenky (docs/design.md, „Rozměry komponent"), ať nevzniká další hodnota.
 */
private val CANDIDATE_THUMB_SIZE = 56.dp

/**
 * Blok pod názvem zboží z účtenky: štítek, co na takovém zboží je a není ověřené, a vstup do
 * dopárování. Dopárovat smí jen přihlášený (`rematchReceiptItem` vyžaduje účet) — anonym
 * dostane cestu k přihlášení místo tlačítka, které by skončilo chybou.
 */
@Composable
internal fun ReceiptItemNotice(isLoggedIn: Boolean, onRematch: () -> Unit, onLogin: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
    ReceiptItemChip()
    Text(
      stringResource(R.string.receipt_item_notice),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (isLoggedIn) {
      OutlinedButton(onClick = onRematch, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.receipt_item_rematch))
      }
    } else {
      TextButton(onClick = onLogin) { Text(stringResource(R.string.receipt_item_rematch_login)) }
    }
  }
}

/**
 * Výběr zboží, kterým položka z účtenky doopravdy je — našeptávač `productSuggestions` v rozsahu
 * obchodu ([rematchStoreId]), bez zdroje samotného a bez cílů, které server odmítne
 * ([rematchCandidates]). Čtyři stavy: načítání, prázdno (s radou), chyba s opakováním a offline
 * (dopárovat bez signálu nejde — nic se nezahodí, jen se počká na připojení).
 *
 * Posouvá se celý obsah panelu, ne jen seznam — stejný důvod jako u panelu řádku účtenky
 * (`ReceiptLineSheet`): s klávesnicí by jinak návrhy nešly dosáhnout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReceiptItemRematchSheet(viewModel: ProductDetailViewModel, online: Boolean) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  LaunchedEffect(online) {
    if (online && viewModel.rematchLoadError != null) viewModel.retryRematchSuggestions()
  }
  ModalBottomSheet(onDismissRequest = viewModel::closeRematch, sheetState = sheetState) {
    Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      Text(stringResource(R.string.receipt_item_rematch_title), style = MaterialTheme.typography.titleMedium)
      Text(
        stringResource(R.string.receipt_item_rematch_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedTextField(
        value = viewModel.rematchQuery,
        onValueChange = viewModel::onRematchQueryChange,
        label = { Text(stringResource(R.string.receipt_suggestions_search)) },
        singleLine = true,
        enabled = online,
        modifier = Modifier.fillMaxWidth(),
      )
      when {
        !online -> Text(
          stringResource(R.string.receipt_item_rematch_offline),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        viewModel.rematchLoading -> Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(Modifier.size(24.dp))
        }
        viewModel.rematchLoadError != null -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
          Text(
            viewModel.rematchLoadError?.asString().orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
          TextButton(onClick = viewModel::retryRematchSuggestions) { Text(stringResource(R.string.common_retry)) }
        }
        viewModel.rematchItems.isEmpty() -> Text(
          stringResource(R.string.receipt_item_rematch_empty),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Obyčejný Column: návrhů je pár (server je omezuje) a uvnitř posouvaného panelu by
        // LazyColumn potřeboval pevnou výšku.
        else -> Column {
          viewModel.rematchItems.forEach { candidate ->
            CandidateRow(candidate, onClick = { viewModel.chooseRematchTarget(candidate) })
            HorizontalDivider()
          }
        }
      }
    }
  }

  viewModel.rematchTarget?.let { target ->
    RematchConfirmDialog(viewModel, sourceName = viewModel.product?.name.orEmpty(), target = target)
  }
}

@Composable
private fun CandidateRow(candidate: ProductSummary, onClick: () -> Unit) {
  val netContent = rememberNetContentLabeler()(candidate.netContentValue, candidate.netContentUom, candidate.isVariableWeight)
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.sm),
    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    ProductThumb(
      name = candidate.name,
      photos = candidate.photos,
      externalImage = candidate.externalImage,
      size = CANDIDATE_THUMB_SIZE,
      placeholderIcon = if (candidate.isReceiptItem) R.drawable.ic_receipt else R.drawable.ic_picture,
    )
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
      Text(
        listOfNotNull(candidate.brand?.name, candidate.name, netContent).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        candidate.category.name,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      // Cílem smí být i jiné zboží z účtenky (server to dovolí), ale musí být vidět, že ani
      // ono nemá ověřené náležitosti.
      if (candidate.isReceiptItem) ReceiptItemBadge()
    }
  }
}

/** Potvrzení před dopárováním — přesouvá data, takže omylem klepnutý řádek nesmí rovnou odeslat. */
@Composable
private fun RematchConfirmDialog(viewModel: ProductDetailViewModel, sourceName: String, target: ProductSummary) {
  AlertDialog(
    onDismissRequest = viewModel::cancelRematchTarget,
    title = { Text(stringResource(R.string.receipt_item_rematch_confirm_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(R.string.receipt_item_rematch_confirm_text, sourceName, target.name))
        viewModel.rematchError?.let {
          Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
      }
    },
    confirmButton = {
      TextButton(onClick = viewModel::confirmRematch, enabled = !viewModel.rematchSaving) {
        if (viewModel.rematchSaving) CircularProgressIndicator(Modifier.size(18.dp))
        else Text(stringResource(R.string.receipt_item_rematch_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = viewModel::cancelRematchTarget, enabled = !viewModel.rematchSaving) {
        Text(stringResource(R.string.common_cancel))
      }
    },
  )
}
