package cz.kvalitacena.ui.receipt.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import cz.kvalitacena.network.ReceiptLine
import cz.kvalitacena.ui.common.globalGtinOrNull
import cz.kvalitacena.ui.common.rememberNetContentLabeler
import cz.kvalitacena.ui.theme.Spacing

/** Nejvyšší výška seznamu návrhů v panelu, ať se vejde i s klávesnicí. */
private val SUGGESTIONS_MAX_HEIGHT = 320.dp

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
 * Offline: akce selžou s chybou v panelu (návrhy) nebo nad seznamem (akce řádku), panel se
 * nezavře — v obchodě bez signálu jde zkusit znovu.
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
      Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
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

      val product = line.product
      val offCandidate = line.offCandidate
      when {
        line.status == "IGNORED" -> Button(
          onClick = { viewModel.unmatch(line); viewModel.advance() },
          modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.receipt_line_restore)) }

        line.status == "MATCHED" -> product?.let {
          OutlinedButton(onClick = { viewModel.closeSheet(); onProductClick(it.id) }, modifier = Modifier.fillMaxWidth()) {
            Text(productLabel(it))
          }
        }

        !viewModel.sheetSearching && line.isOffSuggestion() -> {
          if (offCandidate != null) OffCandidateCard(offCandidate)
          Text(
            stringResource(R.string.receipt_line_off_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          SuggestionActions(line, viewModel)
        }

        !viewModel.sheetSearching && product != null -> {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              productLabel(product),
              style = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.padding(Spacing.md),
            )
          }
          if (line.isFuzzyGuess()) {
            Text(
              stringResource(R.string.receipt_line_sheet_fuzzy_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          SuggestionActions(line, viewModel)
        }

        else -> ProductSearch(line, viewModel, onCreateProduct)
      }
    }
  }
}

/** „Ano, je to ono" a „Jiné zboží" u návrhu; „Není zboží" jako tichá třetí možnost. */
@Composable
private fun SuggestionActions(line: ReceiptLine, viewModel: ReceiptDetailViewModel) {
  Button(onClick = { viewModel.acceptSuggestion(line) }, modifier = Modifier.fillMaxWidth()) {
    Text(stringResource(R.string.receipt_line_sheet_accept))
  }
  OutlinedButton(onClick = viewModel::showOtherProducts, modifier = Modifier.fillMaxWidth()) {
    Text(stringResource(R.string.receipt_line_sheet_other))
  }
  TextButton(onClick = { viewModel.ignoreFromSheet(line) }, modifier = Modifier.fillMaxWidth()) {
    Text(stringResource(R.string.receipt_line_sheet_not_product))
  }
}

/** Hledání zboží k řádku: nejdřív podle textu z účtenky, pak podle toho, co uživatel napíše. */
@Composable
private fun ProductSearch(line: ReceiptLine, viewModel: ReceiptDetailViewModel, onCreateProduct: () -> Unit) {
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
    else -> LazyColumn(Modifier.heightIn(max = SUGGESTIONS_MAX_HEIGHT)) {
      items(viewModel.suggestionItems, key = { it.id }) { product ->
        Text(
          productLabel(product),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.fillMaxWidth().clickable { viewModel.chooseProduct(product) }.padding(vertical = Spacing.md),
        )
        HorizontalDivider()
      }
    }
  }
  Button(onClick = onCreateProduct, modifier = Modifier.fillMaxWidth()) {
    Text(stringResource(R.string.receipt_line_create))
  }
  TextButton(onClick = { viewModel.ignoreFromSheet(line) }, modifier = Modifier.fillMaxWidth()) {
    Text(stringResource(R.string.receipt_line_sheet_not_product))
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
