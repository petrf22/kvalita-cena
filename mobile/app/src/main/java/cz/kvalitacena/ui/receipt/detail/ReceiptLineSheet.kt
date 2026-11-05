package cz.kvalitacena.ui.receipt.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cz.kvalitacena.R
import cz.kvalitacena.network.ExternalProductCandidate
import cz.kvalitacena.network.ProductSummary
import cz.kvalitacena.network.ReceiptLine
import cz.kvalitacena.ui.common.ReceiptItemBadge
import cz.kvalitacena.ui.common.globalGtinOrNull
import cz.kvalitacena.ui.common.isReceiptItem
import cz.kvalitacena.ui.common.rememberNetContentLabeler
import cz.kvalitacena.ui.theme.Spacing

/** Náhled fotky z Open Food Facts — rozměr komponenty, ne rozestup (docs/design.md). */
private val OFF_THUMB_SIZE = 56.dp

/**
 * Spodní panel jednoho řádku účtenky — výchozí akce po ťuknutí na řádek a zároveň krok
 * průvodce „Doplnit nerozpoznané". Ukazuje jen to, co řádek potřebuje:
 * - appka zboží nezná → rovnou návrhy podle textu z účtenky, hledání, založení nového zboží
 *   a „Není zboží" (záloha, taška),
 * - návrh nebo odhad → navržené zboží s „Ano, je to ono" a „Jiné zboží",
 * - návrh z Open Food Facts → náhled z OFF s povinnou atribucí; zboží vznikne až potvrzením,
 * - ignorovaný řádek → vrácení mezi nespárované.
 *
 * Zboží z účtenky (ui/common/ReceiptItem.kt) má v návrzích i u spárovaného řádku štítek —
 * vybrat ho jde, ale je vidět, že nemá ověřené náležitosti.
 *
 * Offline: akce selžou s chybou v panelu (návrhy) nebo nad seznamem (akce řádku), panel se
 * nezavře — v obchodě bez signálu jde zkusit znovu.
 *
 * Posouvá se CELÝ obsah panelu, ne jen seznam návrhů: na malém displeji nebo s otevřenou
 * klávesnicí by jinak hlavička, hledání a návrhy vyčerpaly výšku a „Založit nové zboží"
 * s „Není zboží" pod nimi by nešly vůbec dosáhnout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReceiptLineSheet(
  line: ReceiptLine,
  currency: String?,
  viewModel: ReceiptDetailViewModel,
  onCreateProduct: () -> Unit,
  onProductClick: (String) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = viewModel::closeSheet, sheetState = sheetState) {
    Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      viewModel.guidePosition?.let { (index, total) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
          Text(
            stringResource(R.string.receipt_guide_progress, index, total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
          )
          TextButton(onClick = viewModel::advance) { Text(stringResource(R.string.receipt_guide_skip)) }
        }
      }
      Text(line.shownLabel, style = MaterialTheme.typography.titleMedium)
      LineSource(line.rawLabelIfDifferent, globalGtinOrNull(line.internalCode))
      LinePriceSummary(line, currency)
      line.skipReason?.let {
        Text(skipReasonText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
      // Chyba akce patří k řádku, na kterém panel zůstal — bez ní by výpadek sítě vypadal,
      // jako by ťuknutí nic neudělalo (obchod bez signálu).
      viewModel.actionError?.let {
        Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
      val busy = line.id in viewModel.busyLineIds

      val product = line.product
      val offCandidate = line.offCandidate
      when {
        line.status == "IGNORED" -> Button(
          onClick = { viewModel.restoreFromSheet(line) },
          enabled = !busy,
          modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.receipt_line_restore)) }

        line.status == "MATCHED" -> product?.let {
          OutlinedButton(onClick = { viewModel.closeSheet(); onProductClick(it.id) }, modifier = Modifier.fillMaxWidth()) {
            Text(productLabel(it))
          }
          if (it.isReceiptItem) ReceiptItemBadge()
        }

        !viewModel.sheetSearching && line.isOffSuggestion() -> {
          if (offCandidate != null) OffCandidateCard(offCandidate)
          Text(
            stringResource(R.string.receipt_line_off_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          SuggestionActions(line, viewModel, busy)
        }

        !viewModel.sheetSearching && product != null -> {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
              Text(productLabel(product), style = MaterialTheme.typography.bodyLarge)
              if (product.isReceiptItem) ReceiptItemBadge()
            }
          }
          if (line.isFuzzyGuess()) {
            Text(
              stringResource(R.string.receipt_line_sheet_fuzzy_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          SuggestionActions(line, viewModel, busy)
        }

        else -> ProductSearch(line, viewModel, onCreateProduct, busy)
      }
    }
  }
}

/** „Ano, je to ono" a „Jiné zboží" u návrhu; „Není zboží" jako tichá třetí možnost. */
@Composable
private fun SuggestionActions(line: ReceiptLine, viewModel: ReceiptDetailViewModel, busy: Boolean) {
  Button(onClick = { viewModel.acceptSuggestion(line) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
    Text(stringResource(R.string.receipt_line_sheet_accept))
  }
  OutlinedButton(onClick = viewModel::showOtherProducts, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
    Text(stringResource(R.string.receipt_line_sheet_other))
  }
  TextButton(onClick = { viewModel.ignoreFromSheet(line) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
    Text(stringResource(R.string.receipt_line_sheet_not_product))
  }
}

/** Hledání zboží k řádku: nejdřív podle textu z účtenky, pak podle toho, co uživatel napíše. */
@Composable
private fun ProductSearch(
  line: ReceiptLine,
  viewModel: ReceiptDetailViewModel,
  onCreateProduct: () -> Unit,
  busy: Boolean,
) {
  if (line.publishesAsReceiptItem()) {
    // Párovat není povinné — bez toho by hledání vypadalo jako jediná cesta, jak cenu zapsat.
    Text(
      stringResource(R.string.receipt_line_sheet_publish_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
  OutlinedTextField(
    value = viewModel.suggestionQuery,
    onValueChange = viewModel::onSuggestionQueryChange,
    label = { Text(stringResource(R.string.receipt_suggestions_search)) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
  )
  when {
    viewModel.suggestionsLoading -> Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(Modifier.size(24.dp))
    }
    viewModel.suggestionsError != null -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
      Text(
        viewModel.suggestionsError?.asString().orEmpty(),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
      )
      TextButton(onClick = viewModel::retrySuggestions) { Text(stringResource(R.string.common_retry)) }
    }
    viewModel.suggestionItems.isEmpty() -> Text(
      stringResource(R.string.receipt_suggestions_empty),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Obyčejný Column: návrhů je jen pár (server je omezuje) a uvnitř posouvaného panelu
    // by LazyColumn potřeboval pevnou výšku — právě ta tlačila akce pod okraj.
    else -> Column {
      viewModel.suggestionItems.forEach { product ->
        SuggestionRow(product, enabled = !busy, onClick = { viewModel.chooseProduct(product) })
        HorizontalDivider()
      }
    }
  }
  Button(onClick = onCreateProduct, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
    Text(stringResource(R.string.receipt_line_create))
  }
  TextButton(onClick = { viewModel.ignoreFromSheet(line) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
    Text(stringResource(R.string.receipt_line_sheet_not_product))
  }
}

/** Jeden návrh zboží k řádku — u zboží z účtenky se štítkem, ať je vidět, že není ověřené. */
@Composable
private fun SuggestionRow(product: ProductSummary, enabled: Boolean, onClick: () -> Unit) {
  Column(
    Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = Spacing.md),
    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
  ) {
    Text(productLabel(product), style = MaterialTheme.typography.bodyMedium)
    if (product.isReceiptItem) ReceiptItemBadge()
  }
}

/** Náhled zboží z Open Food Facts — název, značka, gramáž, fotka a povinná atribuce (ODbL). */
@Composable
private fun OffCandidateCard(candidate: ExternalProductCandidate) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(Modifier.padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
      candidate.image?.let { image ->
        AsyncImage(
          model = image.thumbnailUrl,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.size(OFF_THUMB_SIZE),
        )
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(offCandidateLabel(candidate), style = MaterialTheme.typography.bodyLarge)
        Text(
          candidate.attribution,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** Značka · název · gramáž z OFF; bez názvu v jazyce appky (nameLang) aspoň to, co OFF má. */
@Composable
internal fun offCandidateLabel(candidate: ExternalProductCandidate): String {
  val netContent = rememberNetContentLabeler()(candidate.netContentValue, candidate.netContentUom, false)
  return listOfNotNull(candidate.brandName, candidate.name, netContent).joinToString(" · ")
}

/** Nadpis skupiny řádků s počtem; hotové a ignorované jdou sbalit. */
@Composable
internal fun GroupHeader(group: LineGroup, count: Int, collapsible: Boolean, expanded: Boolean, onToggle: () -> Unit) {
  val title = stringResource(
    when (group) {
      LineGroup.TO_RESOLVE -> R.string.receipt_group_to_resolve
      LineGroup.TO_CHECK -> R.string.receipt_group_to_check
      LineGroup.READY -> R.string.receipt_group_ready
      LineGroup.DONE -> R.string.receipt_group_done
      LineGroup.IGNORED -> R.string.receipt_group_ignored
    },
    count,
  )
  val modifier = if (collapsible) Modifier.fillMaxWidth().clickable(onClick = onToggle) else Modifier.fillMaxWidth()
  Row(modifier.padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
    if (collapsible) {
      Text(
        stringResource(if (expanded) R.string.receipt_group_collapse else R.string.receipt_group_expand),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}
