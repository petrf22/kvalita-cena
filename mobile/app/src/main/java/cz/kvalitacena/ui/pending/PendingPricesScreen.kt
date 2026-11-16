package cz.kvalitacena.ui.pending

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.network.ObservationPriceInput
import cz.kvalitacena.price.PendingPrice
import cz.kvalitacena.price.PriceWork
import cz.kvalitacena.ui.common.LabelValueRow
import cz.kvalitacena.ui.common.NavigationResults
import cz.kvalitacena.ui.common.OfflineBanner
import cz.kvalitacena.ui.common.SELECTABLE_VARIABLE_WEIGHT_QUANTITY_BASES
import cz.kvalitacena.ui.common.StateMessage
import cz.kvalitacena.ui.common.formatRelativeDate
import cz.kvalitacena.ui.common.priceKindLabel
import cz.kvalitacena.ui.common.quantityBasisLabel
import cz.kvalitacena.ui.common.rememberMoneyFormatter
import cz.kvalitacena.ui.theme.Spacing
import java.text.NumberFormat

/**
 * „Čekající ceny" — fronta cen zapsaných bez signálu (`price/PendingPriceStore`). Odejdou samy
 * (`PriceUploadWorker`); obrazovka ukazuje, co ještě čeká, a hlavně nabízí vyřešit položky,
 * které bez uživatele odejít nemůžou: doplnit neznámé zboží, říct, za co byla cena u zboží
 * na váhu, přihlásit se, zkusit znovu, nebo cenu smazat.
 *
 * Čtyři stavy (docs/design.md): prázdná fronta a offline mají vlastní text. Načítání a chyba
 * načtení tu poctivě NEJSOU — seznam je soubor v telefonu, který `PendingPriceStore` čte
 * synchronně už při startu (není na co čekat) a nečitelnou položku přeskočí (není co opakovat).
 * Chyba patří k jednotlivé položce (odmítnutí serverem), ne k obrazovce.
 */
@Composable
fun PendingPricesScreen(
  onCompleteProduct: (barcode: String?) -> Unit,
  onOpenAccount: () -> Unit,
) {
  val appContext = LocalContext.current.applicationContext
  val viewModel: PendingPricesViewModel = viewModel(
    factory = viewModelFactory {
      initializer {
        PendingPricesViewModel(
          store = AppContainer.pendingPrices,
          authRepository = AppContainer.authRepository,
          uploadRunning = PriceWork.uploadRunning(appContext),
          scheduleUpload = { PriceWork.enqueueUpload(appContext) },
        )
      }
    },
  )
  val pending by viewModel.pending.collectAsState()
  val currentUid by viewModel.currentUid.collectAsState()
  val uploading by viewModel.uploading.collectAsState()
  val loggedIn by AppContainer.authRepository.isLoggedIn.collectAsState()
  val online by AppContainer.networkMonitor.online.collectAsState()
  var confirmDelete by remember { mutableStateOf<PendingPrice?>(null) }

  // Návrat z formuláře zboží (NavigationResults, stejný mechanismus jako PriceEntryScreen).
  // Vyzvedne se vždy, i když ViewModel o otevřeném formuláři neví — jinak by výsledek zůstal
  // ležet a příští zápis ceny by si ho vzal za své.
  LaunchedEffect(Unit) {
    viewModel.onShown()
    NavigationResults.newProduct?.let { product ->
      NavigationResults.newProduct = null
      NavigationResults.newProductCreated = false
      NavigationResults.productAlias = null
      viewModel.onProductChosen(product)
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    if (pending.isEmpty()) {
      StateMessage(
        title = stringResource(R.string.pending_prices_empty_title),
        text = stringResource(R.string.pending_prices_empty_text),
      )
      return@Column
    }
    if (!online) OfflineBanner(stringResource(R.string.pending_prices_offline))

    // Nejdřív to, co bez uživatele neodejde; uvnitř skupin pořadí zápisu (PendingPriceStore).
    val (attention, waiting) = pending.partition { needsAttention(it, loggedIn, currentUid) }
    val card: @Composable (PendingPrice) -> Unit = { price ->
      PendingPriceCard(
        price = price,
        view = pendingPriceView(price, loggedIn, currentUid, online, uploading),
        onCompleteProduct = {
          viewModel.startCompletingProduct(price.id)
          onCompleteProduct(price.barcode)
        },
        onChooseBasis = { basis -> viewModel.chooseBasis(price.id, basis) },
        onLogIn = onOpenAccount,
        onRetry = { viewModel.retry(price.id) },
        onDelete = { confirmDelete = price },
      )
    }
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(Spacing.lg),
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      item {
        Header(
          uploading = uploading && online,
          canSendNow = canSendNow(pending, loggedIn, currentUid, online, uploading),
          onSendNow = viewModel::sendNow,
        )
      }
      if (attention.isNotEmpty()) {
        item { SectionTitle(stringResource(R.string.pending_prices_section_attention)) }
        items(attention, key = { it.id }) { card(it) }
      }
      if (waiting.isNotEmpty()) {
        item { SectionTitle(stringResource(R.string.pending_prices_section_waiting)) }
        items(waiting, key = { it.id }) { card(it) }
      }
    }
  }

  confirmDelete?.let { price ->
    AlertDialog(
      onDismissRequest = { confirmDelete = null },
      title = { Text(stringResource(R.string.pending_price_delete_title)) },
      text = { Text(stringResource(R.string.pending_price_delete_text)) },
      confirmButton = {
        TextButton(onClick = { viewModel.delete(price.id); confirmDelete = null }) {
          Text(stringResource(R.string.common_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.common_cancel)) }
      },
    )
  }
}

/** K čemu obrazovka je, a „Odeslat teď" / „Odesílám…", když to má smysl ([canSendNow]). */
@Composable
private fun Header(uploading: Boolean, canSendNow: Boolean, onSendNow: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
    Text(
      stringResource(R.string.pending_prices_intro),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when {
      uploading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.pending_prices_sending), style = MaterialTheme.typography.bodyMedium)
      }
      canSendNow -> Button(onClick = onSendNow, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.pending_prices_send_now))
      }
    }
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.sm))
}

@Composable
private fun PendingPriceCard(
  price: PendingPrice,
  view: PendingPriceView,
  onCompleteProduct: () -> Unit,
  onChooseBasis: (String) -> Unit,
  onLogIn: () -> Unit,
  onRetry: () -> Unit,
  onDelete: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
      // Zboží dohledané až po připojení zatím nemá název — kód je jediné, podle čeho ho uživatel pozná.
      Text(
        price.productName ?: stringResource(R.string.pending_price_barcode, price.barcode.orEmpty()),
        style = MaterialTheme.typography.titleSmall,
      )
      Text(price.storeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

      val amountFormatter = rememberAmountFormatter(price.currency)
      price.prices.forEach { input ->
        LabelValueRow(label = priceKindLabel(input.priceKind), value = amountText(input, amountFormatter))
      }
      Text(
        "${stringResource(R.string.pending_price_observed_label)} ${formatRelativeDate(price.observedAt)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Row(
        modifier = Modifier.padding(top = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
      ) {
        if (view.sending) CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(
          view.title.asString(),
          style = MaterialTheme.typography.bodyMedium,
          // Chybová barva i pro „doplň zboží": bez zásahu cena neodejde, stejně jako u účtenek
          // čekajících na souhlas (ReceiptListScreen). Čekající položka chybou není.
          color = if (view.needsAttention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
      }
      view.detail?.let {
        Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }

      if (PendingPriceAction.CHOOSE_BASIS in view.actions) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
          SELECTABLE_VARIABLE_WEIGHT_QUANTITY_BASES.forEach { basis ->
            OutlinedButton(onClick = { onChooseBasis(basis) }) { Text(quantityBasisLabel(basis)) }
          }
        }
      }
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        itemVerticalAlignment = Alignment.CenterVertically,
      ) {
        view.actions.forEach { action ->
          when (action) {
            PendingPriceAction.COMPLETE_PRODUCT -> Button(onClick = onCompleteProduct) {
              Text(stringResource(R.string.pending_price_action_complete_product))
            }
            PendingPriceAction.LOG_IN -> Button(onClick = onLogIn) {
              Text(stringResource(R.string.pending_price_action_log_in))
            }
            PendingPriceAction.RETRY -> TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
            PendingPriceAction.DELETE -> TextButton(onClick = onDelete) { Text(stringResource(R.string.common_delete)) }
            // Volba základu je výš jako samostatná řada tlačítek.
            PendingPriceAction.CHOOSE_BASIS -> Unit
          }
        }
      }
    }
  }
}

/**
 * Částka v měně položky. Bez známé měny (položka zapsaná dřív, než ji fronta ukládala) holé
 * číslo — měna z Nastavení by u ceny z jiné země lhala.
 */
@Composable
private fun rememberAmountFormatter(currency: String?): NumberFormat {
  if (currency != null) return rememberMoneyFormatter(currency)
  val locale = LocalConfiguration.current.locales[0]
  return remember(locale) {
    NumberFormat.getNumberInstance(locale).apply {
      minimumFractionDigits = 2
      maximumFractionDigits = 2
    }
  }
}

@Composable
private fun amountText(input: ObservationPriceInput, formatter: NumberFormat): String {
  val qty = input.multibuyQty
  val total = input.multibuyTotal
  if (qty != null && total != null) {
    return stringResource(R.string.pending_price_multibuy_amount, qty, formatter.format(total))
  }
  return input.priceAmount?.let { formatter.format(it) }.orEmpty()
}
