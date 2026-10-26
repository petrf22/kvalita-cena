package cz.kvalitacena.ui.receipt.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.network.ProductSummary
import cz.kvalitacena.network.Receipt
import cz.kvalitacena.network.ReceiptLine
import cz.kvalitacena.ui.common.NavigationResults
import cz.kvalitacena.ui.common.StorePicker
import cz.kvalitacena.ui.common.ZoomableImage
import cz.kvalitacena.ui.common.rememberMoneyFormatter
import cz.kvalitacena.ui.common.rememberNetContentLabeler
import cz.kvalitacena.ui.common.storeLabel
import cz.kvalitacena.ui.receipt.OfflineBanner
import cz.kvalitacena.ui.receipt.ReceiptLoginRequired
import cz.kvalitacena.ui.receipt.ReceiptStateMessage
import cz.kvalitacena.ui.receipt.formatReceiptDateTime
import cz.kvalitacena.ui.receipt.processingStatusExplanation
import cz.kvalitacena.ui.receipt.processingStatusLabel
import cz.kvalitacena.ui.receipt.receiptStorePrefill
import cz.kvalitacena.ui.theme.Spacing
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Rozměr náhledu dílu účtenky v řádku obrázků — rozměr komponenty, ne rozestup (docs/design.md). */
private val THUMB_WIDTH = 72.dp
private val THUMB_HEIGHT = 96.dp

/** Nejvyšší výška seznamu v našeptávači zboží, ať se dialog vejde i s klávesnicí. */
private val SUGGESTIONS_MAX_HEIGHT = 320.dp

/**
 * Detail účtenky: hlavička s obchodem a datem nákupu (obojí se stane údajem všech cen
 * z účtenky), řádky se stavem párování a akcemi, potvrzení spárovaných řádků a náhled obrázků,
 * dokud je server nesmaže (docs/soukromi.md, „Účtenky" — 30 dní od zpracování).
 */
@Composable
fun ReceiptDetailScreen(
  receiptId: String,
  onCreateProduct: (name: String, storeId: String?) -> Unit,
  onAddStore: () -> Unit,
  onProductClick: (String) -> Unit,
  onRescan: () -> Unit,
  onDeleted: () -> Unit,
  onOpenAccount: () -> Unit,
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

  val viewModel: ReceiptDetailViewModel = viewModel(
    factory = viewModelFactory {
      initializer { ReceiptDetailViewModel(receiptId, AppContainer.graphQlClient, AppContainer.mediaClient) }
    },
  )
  val online by AppContainer.networkMonitor.online.collectAsState()

  LaunchedEffect(Unit) {
    NavigationResults.newStore?.let {
      NavigationResults.newStore = null
      viewModel.onStoreSelected(it)
    }
    NavigationResults.newProduct?.let {
      NavigationResults.newProduct = null
      // Alias pro zápis ceny (PriceEntryScreen) tu nedává smysl — označení se učí z řádku.
      NavigationResults.productAlias = null
      viewModel.onProductFormResult(it.id, NavigationResults.newProductCreated)
    }
  }
  LaunchedEffect(viewModel.deleted) {
    if (viewModel.deleted) onDeleted()
  }
  LaunchedEffect(online) {
    if (online && viewModel.error != null) viewModel.load()
  }

  val receipt = viewModel.receipt
  Column(Modifier.fillMaxSize()) {
    if (!online) OfflineBanner(stringResource(R.string.receipt_detail_offline))
    when {
      viewModel.loading && receipt == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
      viewModel.notFound -> ReceiptStateMessage(
        title = stringResource(R.string.receipt_detail_not_found_title),
        text = stringResource(R.string.receipt_detail_not_found_text),
      )
      receipt == null -> ReceiptStateMessage(
        title = stringResource(if (online) R.string.receipts_error_title else R.string.receipts_offline_title),
        text = if (online) viewModel.error?.asString() else stringResource(R.string.receipt_detail_offline),
        actionLabel = stringResource(R.string.common_retry),
        onAction = viewModel::load,
      )
      else -> ReceiptContent(
        receipt = receipt,
        viewModel = viewModel,
        onCreateProduct = { line ->
          viewModel.startNewProduct(line)
          onCreateProduct(line.labelRaw, receipt.store?.id)
        },
        onAddStore = onAddStore,
        onProductClick = onProductClick,
        onRescan = onRescan,
        modifier = Modifier.weight(1f),
      )
    }
  }

  viewModel.suggestionLine?.let { line ->
    SuggestionsDialog(
      line = line,
      viewModel = viewModel,
      onCreateProduct = {
        viewModel.startNewProduct(line)
        onCreateProduct(line.labelRaw, receipt?.store?.id)
      },
    )
  }
  viewModel.confirmResult?.let { result ->
    AlertDialog(
      onDismissRequest = viewModel::dismissConfirmResult,
      title = { Text(stringResource(R.string.receipt_confirm_result_title)) },
      text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
          Text(stringResource(R.string.receipt_confirm_result_confirmed, result.confirmed))
          if (result.skipped.isNotEmpty()) {
            Text(
              stringResource(R.string.receipt_confirm_result_skipped, result.skipped.size),
              style = MaterialTheme.typography.titleSmall,
            )
            result.skipped.forEach { line ->
              Text(
                "${line.labelRaw} — ${skipReasonText(line.skipReason)}",
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = viewModel::dismissConfirmResult) { Text(stringResource(R.string.common_ok)) } },
    )
  }
}

@Composable
private fun ReceiptContent(
  receipt: Receipt,
  viewModel: ReceiptDetailViewModel,
  onCreateProduct: (ReceiptLine) -> Unit,
  onAddStore: () -> Unit,
  onProductClick: (String) -> Unit,
  onRescan: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var confirmDelete by remember { mutableStateOf(false) }
  var zoomedPath by remember { mutableStateOf<String?>(null) }
  val processed = receipt.processingStatus == "PROCESSED"

  Column(modifier.fillMaxWidth()) {
    LazyColumn(
      Modifier.weight(1f).fillMaxWidth(),
      contentPadding = PaddingValues(Spacing.lg),
      verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      item { HeaderCard(receipt, viewModel, onAddStore, onRescan) }
      if (receipt.imagePaths.isNotEmpty() || receipt.imagesDeletedAt != null) {
        item { ImagesRow(receipt, viewModel, onOpen = { zoomedPath = it }) }
      }
      viewModel.actionError?.let { message ->
        item { Text(message.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
      }
      if (processed) {
        item {
          Text(
            stringResource(R.string.receipt_lines_title, receipt.lines.size),
            style = MaterialTheme.typography.titleMedium,
          )
        }
        if (receipt.lines.isEmpty()) {
          item {
            Text(
              stringResource(R.string.receipt_lines_empty),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        items(receipt.lines, key = { it.id }) { line ->
          LineRow(
            line = line,
            currency = receipt.currency,
            selected = line.id in viewModel.selected,
            busy = line.id in viewModel.busyLineIds,
            onToggle = { viewModel.toggleSelected(line.id) },
            onChoose = { viewModel.openSuggestions(line) },
            onCreate = { onCreateProduct(line) },
            onIgnore = { viewModel.ignore(line) },
            onUnmatch = { viewModel.unmatch(line) },
            onProductClick = onProductClick,
          )
        }
      }
      item {
        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.receipt_delete))
        }
      }
    }
    if (processed && receipt.lines.any { it.status == "SUGGESTED" }) {
      ConfirmBar(receipt, viewModel)
    }
  }

  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text(stringResource(R.string.receipt_delete_title)) },
      text = { Text(stringResource(R.string.receipt_delete_text)) },
      confirmButton = {
        TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text(stringResource(R.string.common_delete)) }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
  }
  zoomedPath?.let { path ->
    Dialog(onDismissRequest = { zoomedPath = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
          ZoomableImage(
            model = viewModel.images[path],
            contentDescription = stringResource(R.string.receipt_image),
            onZoomedChange = {},
            modifier = Modifier.fillMaxSize(),
          )
          IconButton(onClick = { zoomedPath = null }, modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.sm)) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_close))
          }
        }
      }
    }
  }
}

@Composable
private fun HeaderCard(receipt: Receipt, viewModel: ReceiptDetailViewModel, onAddStore: () -> Unit, onRescan: () -> Unit) {
  val homeCountry = AppContainer.countryStore.country
  val money = rememberMoneyFormatter(receipt.currency)
  var pickingDate by remember { mutableStateOf(false) }

  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
      Text(
        receipt.store?.name ?: receipt.storeNameRaw ?: stringResource(R.string.receipt_unknown_store),
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        processingStatusLabel(receipt.processingStatus),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
      )
      receipt.totalAmount?.let {
        Text(stringResource(R.string.receipt_total, money.format(it)), style = MaterialTheme.typography.bodyMedium)
      }
      if (receipt.processingStatus != "PROCESSED") {
        Text(
          processingStatusExplanation(receipt.processingStatus),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (receipt.processingStatus in setOf("UNREADABLE", "NOT_A_RECEIPT", "FAILED")) {
          Button(onClick = onRescan) { Text(stringResource(R.string.receipt_rescan)) }
        }
        return@Column
      }

      HorizontalDivider()
      // Obchod — potvrdit návrh serveru, nebo vybrat jiný. Bez obchodu nejde cenu zapsat.
      val store = receipt.store
      val suggested = receipt.suggestedStore
      when {
        viewModel.storeEditing || (store == null && suggested == null) -> {
          val raw = listOfNotNull(receipt.storeNameRaw, receipt.storeAddressRaw, receipt.storeIcoRaw?.let { stringResource(R.string.receipt_store_ico, it) })
          if (raw.isNotEmpty()) {
            Text(
              stringResource(R.string.receipt_store_raw, raw.joinToString(", ")),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          StorePicker(
            query = viewModel.storeQuery,
            onQueryChange = viewModel::onStoreQueryChange,
            suggestions = viewModel.storeSuggestions,
            searching = viewModel.storeSearching,
            selectedStoreId = store?.id,
            onSelect = viewModel::onStoreSelected,
            onAddNew = {
              NavigationResults.storePrefill = receiptStorePrefill(receipt)
              onAddStore()
            },
            isLoggedIn = true,
            homeCountry = homeCountry,
            modifier = Modifier.fillMaxWidth(),
          )
          if (viewModel.storeEditing) {
            TextButton(onClick = { viewModel.storeEditing = false }) { Text(stringResource(R.string.common_cancel)) }
          }
        }
        store != null -> Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            stringResource(R.string.receipt_store, storeLabel(store, homeCountry)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
          )
          TextButton(onClick = { viewModel.storeEditing = true }, enabled = !viewModel.headerSaving) {
            Text(stringResource(R.string.receipt_change))
          }
        }
        suggested != null -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
          Text(
            stringResource(R.string.receipt_store_suggested, storeLabel(suggested, homeCountry)),
            style = MaterialTheme.typography.bodyMedium,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(onClick = viewModel::confirmSuggestedStore, enabled = !viewModel.headerSaving) {
              Text(stringResource(R.string.receipt_store_confirm))
            }
            TextButton(onClick = { viewModel.storeEditing = true }) { Text(stringResource(R.string.receipt_store_other)) }
          }
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          formatReceiptDateTime(receipt.purchasedAt)?.let { stringResource(R.string.receipt_purchased_at, it) }
            ?: stringResource(R.string.receipt_purchased_at_missing),
          style = MaterialTheme.typography.bodyMedium,
          color = if (receipt.purchasedAt == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { pickingDate = true }, enabled = !viewModel.headerSaving) {
          Text(stringResource(R.string.receipt_change))
        }
      }
      if (viewModel.headerSaving) CircularProgressIndicator(Modifier.size(20.dp))
    }
  }

  if (pickingDate) {
    PurchaseDateTimeDialog(
      initial = receipt.purchasedAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
      onPicked = { pickingDate = false; viewModel.setPurchasedAt(it) },
      onDismiss = { pickingDate = false },
    )
  }
}

/** Datum a pak čas nákupu — dva kroky, Material nemá jeden výběr obojího. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurchaseDateTimeDialog(initial: OffsetDateTime?, onPicked: (OffsetDateTime) -> Unit, onDismiss: () -> Unit) {
  val zone = ZoneId.systemDefault()
  val local = (initial ?: OffsetDateTime.now()).atZoneSameInstant(zone)
  val dateState = rememberDatePickerState(
    initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
  )
  val timeState = rememberTimePickerState(local.hour, local.minute, is24Hour = true)
  var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

  val date = pickedDate
  if (date == null) {
    DatePickerDialog(
      onDismissRequest = onDismiss,
      confirmButton = {
        TextButton(onClick = {
          dateState.selectedDateMillis?.let {
            pickedDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
          }
        }) { Text(stringResource(R.string.common_ok)) }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    ) {
      DatePicker(state = dateState)
    }
  } else {
    AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.receipt_purchase_time)) },
      text = { TimePicker(state = timeState) },
      confirmButton = {
        TextButton(onClick = {
          val time = LocalTime.of(timeState.hour, timeState.minute)
          onPicked(date.atTime(time).atZone(zone).toOffsetDateTime())
        }) { Text(stringResource(R.string.common_ok)) }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
  }
}

@Composable
private fun ImagesRow(receipt: Receipt, viewModel: ReceiptDetailViewModel, onOpen: (String) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
    Text(stringResource(R.string.receipt_images_title), style = MaterialTheme.typography.titleSmall)
    if (receipt.imagePaths.isEmpty()) {
      Text(
        stringResource(R.string.receipt_images_deleted),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      return@Column
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
      receipt.imagePaths.forEach { path ->
        LaunchedEffect(path) { viewModel.loadImage(path) }
        val bytes = viewModel.images[path]
        Box(
          Modifier.size(THUMB_WIDTH, THUMB_HEIGHT)
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = bytes != null) { onOpen(path) },
          contentAlignment = Alignment.Center,
        ) {
          Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {}
          if (bytes == null) {
            CircularProgressIndicator(Modifier.size(20.dp))
          } else {
            AsyncImage(
              model = bytes,
              contentDescription = stringResource(R.string.receipt_image),
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          }
        }
      }
    }
    Text(
      stringResource(R.string.receipt_images_retention),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun LineRow(
  line: ReceiptLine,
  currency: String?,
  selected: Boolean,
  busy: Boolean,
  onToggle: () -> Unit,
  onChoose: () -> Unit,
  onCreate: () -> Unit,
  onIgnore: () -> Unit,
  onUnmatch: () -> Unit,
  onProductClick: (String) -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }
  val money = rememberMoneyFormatter(currency)
  val locale = LocalConfiguration.current.locales[0]
  val quantityFormat = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3 } }

  Card(Modifier.fillMaxWidth()) {
    Row(Modifier.padding(vertical = Spacing.sm), verticalAlignment = Alignment.Top) {
      Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (line.status == "SUGGESTED") {
          Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = !busy)
        }
      }
      Column(Modifier.weight(1f).padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(line.labelRaw, style = MaterialTheme.typography.titleSmall)
        val unitPrice = line.unitPrice?.let { money.format(it) }
        val quantity = line.quantity?.let { quantityFormat.format(it) }
        val detail = when {
          quantity != null && unitPrice != null -> stringResource(
            when (line.quantityUnit) {
              "KG" -> R.string.receipt_line_qty_kg
              "L" -> R.string.receipt_line_qty_l
              else -> R.string.receipt_line_qty_pcs
            },
            quantity,
            unitPrice,
          )
          else -> null
        }
        val total = line.lineTotal?.let { money.format(it) }
        listOfNotNull(detail, total).takeIf { it.isNotEmpty() }?.let {
          Text(it.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall)
        }
        line.discountAmount?.takeIf { it > 0 }?.let {
          Text(stringResource(R.string.receipt_line_discount, money.format(it)), style = MaterialTheme.typography.bodySmall)
        }
        line.product?.let { product ->
          Text(
            productLabel(product),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onProductClick(product.id) },
          )
        }
        LineStatusChip(line)
        line.skipReason?.let {
          Text(skipReasonText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
      }
      Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (busy) {
          CircularProgressIndicator(Modifier.size(20.dp))
        } else if (line.status != "MATCHED") {
          IconButton(onClick = { menuOpen = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.receipt_line_actions))
          }
          DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (line.status != "IGNORED") {
              DropdownMenuItem(
                text = {
                  Text(stringResource(if (line.status == "SUGGESTED") R.string.receipt_line_choose_other else R.string.receipt_line_choose))
                },
                onClick = { menuOpen = false; onChoose() },
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.receipt_line_create)) },
                onClick = { menuOpen = false; onCreate() },
              )
            }
            if (line.status == "SUGGESTED" || line.status == "IGNORED") {
              DropdownMenuItem(
                text = {
                  Text(stringResource(if (line.status == "IGNORED") R.string.receipt_line_restore else R.string.receipt_line_unmatch))
                },
                onClick = { menuOpen = false; onUnmatch() },
              )
            }
            if (line.status != "IGNORED") {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.receipt_line_ignore)) },
                onClick = { menuOpen = false; onIgnore() },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun productLabel(product: ProductSummary): String {
  val netContent = rememberNetContentLabeler()(product.netContentValue, product.netContentUom, product.isVariableWeight)
  return listOfNotNull(product.brand?.name, product.name, netContent).joinToString(" · ")
}

/** Stav řádku jako čip — barvy z tématu, fungují ve světlém i tmavém režimu. */
@Composable
private fun LineStatusChip(line: ReceiptLine) {
  val (label, container, content) = when (line.status) {
    "SUGGESTED" -> Triple(
      stringResource(
        if (line.matchSource == "USER" || line.matchSource == "NEW_PRODUCT") R.string.receipt_line_status_matched_by_you
        else R.string.receipt_line_status_suggested,
      ),
      MaterialTheme.colorScheme.secondaryContainer,
      MaterialTheme.colorScheme.onSecondaryContainer,
    )
    "MATCHED" -> Triple(
      stringResource(if (line.confirmed) R.string.receipt_line_status_confirmed else R.string.receipt_line_status_matched),
      MaterialTheme.colorScheme.primaryContainer,
      MaterialTheme.colorScheme.onPrimaryContainer,
    )
    "IGNORED" -> Triple(
      stringResource(R.string.receipt_line_status_ignored),
      MaterialTheme.colorScheme.surfaceVariant,
      MaterialTheme.colorScheme.onSurfaceVariant,
    )
    else -> Triple(
      stringResource(R.string.receipt_line_status_unmatched),
      MaterialTheme.colorScheme.tertiaryContainer,
      MaterialTheme.colorScheme.onTertiaryContainer,
    )
  }
  Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs))
  }
}

@Composable
private fun skipReasonText(reason: String?): String = stringResource(
  when (reason) {
    "DUPLICATE_SAME_DAY" -> R.string.receipt_skip_duplicate
    "RECEIPT_LINE_PRICE_MISSING" -> R.string.receipt_skip_price_missing
    "RECEIPT_LINE_NOT_MATCHED" -> R.string.receipt_skip_not_matched
    else -> R.string.receipt_skip_other
  },
)

@Composable
private fun ConfirmBar(receipt: Receipt, viewModel: ReceiptDetailViewModel) {
  val missingStore = receipt.store == null
  val missingDate = receipt.purchasedAt == null
  Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
      if (missingStore || missingDate) {
        Text(
          stringResource(if (missingStore) R.string.receipt_confirm_needs_store else R.string.receipt_confirm_needs_date),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
      Button(
        onClick = viewModel::confirm,
        enabled = !missingStore && !missingDate && viewModel.confirmableCount > 0 && !viewModel.confirming,
        modifier = Modifier.fillMaxWidth(),
      ) {
        if (viewModel.confirming) CircularProgressIndicator(Modifier.size(20.dp))
        else Text(stringResource(R.string.receipt_confirm, viewModel.confirmableCount))
      }
    }
  }
}

/** Výběr zboží k řádku: nejdřív podle textu z účtenky, pak podle toho, co uživatel napíše. */
@Composable
private fun SuggestionsDialog(line: ReceiptLine, viewModel: ReceiptDetailViewModel, onCreateProduct: () -> Unit) {
  AlertDialog(
    onDismissRequest = viewModel::closeSuggestions,
    title = { Text(line.labelRaw) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedTextField(
          value = viewModel.suggestionQuery,
          onValueChange = viewModel::onSuggestionQueryChange,
          label = { Text(stringResource(R.string.receipt_suggestions_search)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        when {
          viewModel.suggestionsLoading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp))
          }
          viewModel.suggestionsError != null -> Text(
            viewModel.suggestionsError?.asString().orEmpty(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
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
                modifier = Modifier.fillMaxWidth().clickable { viewModel.chooseProduct(product) }.padding(vertical = Spacing.sm),
              )
              HorizontalDivider()
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onCreateProduct) { Text(stringResource(R.string.receipt_line_create)) } },
    dismissButton = { TextButton(onClick = viewModel::closeSuggestions) { Text(stringResource(R.string.common_cancel)) } },
  )
}
