package cz.kvalitacena.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.network.ExternalLink
import cz.kvalitacena.network.PriceCurrent
import cz.kvalitacena.ui.common.NavigationResults
import cz.kvalitacena.ui.common.PhotoGallery
import cz.kvalitacena.ui.common.PhotoPicker
import cz.kvalitacena.ui.common.QualityBadge
import cz.kvalitacena.ui.common.StarRatingInput
import cz.kvalitacena.ui.common.formatRelativeDate
import cz.kvalitacena.ui.common.openUrl
import cz.kvalitacena.ui.common.priceKindLabel
import cz.kvalitacena.ui.common.rememberMoneyFormatter

/**
 * Detail produktu: název, fotky, graf vývoje ceny (rozklikávací rozsah), nejlevnější obchod,
 * odkazy do otevřených databází (Open Food Facts), počet hlášení/cena/datum/obchod po řádcích
 * — klik na obchod vede na jeho detail (adresa, mapa, fotky), viz StoreDetailScreen.
 * Hodnocení kvality hvězdičkami (5 nejlepší) vyžaduje přihlášení.
 */
@Composable
fun ProductDetailScreen(
  productId: String,
  onWriteObservation: () -> Unit,
  onNavigateToAccount: () -> Unit,
  onStoreClick: (String) -> Unit,
  onEditProduct: (String) -> Unit,
) {
  val viewModel: ProductDetailViewModel = viewModel(
    factory = viewModelFactory { initializer { ProductDetailViewModel(AppContainer.graphQlClient, productId) } },
  )
  val context = LocalContext.current
  val accessToken by AppContainer.authRepository.accessToken.collectAsState()
  val isLoggedIn = accessToken != null

  // Po návratu z editace (ProductFormScreen productId != null) vyzvedne výsledek stejným vzorem
  // jako StoreDetailScreen NavigationResults.updatedStore.
  LaunchedEffect(Unit) {
    NavigationResults.updatedProduct?.let {
      viewModel.onProductUpdated(it)
      NavigationResults.updatedProduct = null
    }
  }

  when {
    viewModel.loading -> {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }

    viewModel.notFound -> {
      Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.product_not_found), style = MaterialTheme.typography.bodyLarge)
      }
    }

    else -> {
      val product = viewModel.product!!
      Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(product.name, style = MaterialTheme.typography.headlineSmall)

        Gap()
        PhotoGallery(photos = product.photos, onPhotosChange = viewModel::onPhotosChange, modifier = Modifier.fillMaxWidth())
        if (isLoggedIn) {
          PhotoPicker(
            recordType = "PRODUCT",
            recordId = productId,
            existingPhotoCount = product.photos.size,
            onUploaded = { photo -> viewModel.onPhotosChange(product.photos + photo) },
            modifier = Modifier.padding(top = 4.dp),
          )
        }
        Gap()

        // Atribuce zdroje/licence MUSÍ být vidět — ODbL (docs/datovy-model.md).
        product.catalogAttribution?.let { attribution ->
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (product.externalImage != null && product.photos.isEmpty()) {
              AsyncImage(
                model = product.externalImage.thumbnailUrl,
                contentDescription = product.name,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop,
              )
            }
            Text(attribution, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Gap()
        }

        val subtitle = listOfNotNull(product.brand?.name, product.category.name).joinToString(" · ")
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium)

        // --- Štítky uživatelské vrstvy (docs/datovy-model.md) + nahlášení ---
        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          if (!product.verified) {
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.common_unverified)) })
          }
          if (product.editedByMe) {
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.product_edited_by_me)) })
          }
          if (product.status == "DRAFT") {
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.product_draft_pending)) })
          }
          if (isLoggedIn) {
            TextButton(onClick = { onEditProduct(productId) }) {
              Text(stringResource(R.string.common_edit))
            }
            TextButton(onClick = { viewModel.flagProduct() }, enabled = !viewModel.flagging) {
              Text(stringResource(R.string.common_report))
            }
          }
        }
        viewModel.flagMessage?.let {
          Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Gap()

        // --- Kvalita ---
        QualityBadge(average = product.quality?.average, count = product.quality?.count ?: 0)
        StarRatingInput(
          value = product.myQualityRating,
          onRate = { stars -> if (isLoggedIn) viewModel.rate(stars) else onNavigateToAccount() },
          modifier = Modifier.padding(top = 4.dp),
        )
        if (!isLoggedIn) {
          Text(
            stringResource(R.string.product_quality_requires_login),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        viewModel.ratingError?.let {
          Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Gap()
        HorizontalDivider()
        Gap()

        // --- Graf vývoje ceny ---
        Text(stringResource(R.string.product_price_trend), style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          CHART_RANGES.forEach { (days, labelRes) ->
            FilterChip(
              selected = viewModel.selectedDays == days,
              onClick = { viewModel.onDaysChange(days) },
              label = { Text(stringResource(labelRes)) },
            )
          }
        }
        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf("REGULAR", "PROMO").forEach { kind ->
            FilterChip(
              selected = viewModel.selectedPriceKind == kind,
              onClick = { viewModel.onPriceKindChange(kind) },
              label = { Text(priceKindLabel(kind)) },
            )
          }
        }
        Gap()
        if (viewModel.historyLoading) {
          Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        } else {
          val history = viewModel.history
          // Graf jednu řadu nikdy nemíchá napříč měnami — když appka přepočítává (displayCurrency
          // vyplněná), substituují se OBĚ pole najednou, se svým vlastním denním kurzem
          // (PricePoint.convertedUnitPrice), viz docs/lokalizace.md. Chybí-li kurz pro konkrétní
          // den, ten bod spadne zpět na originál — vzácný okrajový případ, ne důvod řadu zahodit.
          val chartPoints = if (history?.displayCurrency != null) {
            history.points.map { it.copy(unitPrice = it.convertedUnitPrice ?: it.unitPrice) }
          } else {
            history?.points ?: emptyList()
          }
          PriceChart(
            points = chartPoints,
            currency = history?.displayCurrency ?: history?.currency,
            modifier = Modifier.fillMaxWidth(),
          )
        }
        Gap()
        HorizontalDivider()
        Gap()

        // --- Nejlevněji ---
        val stats = product.stats
        Text(stringResource(R.string.product_cheapest_title), style = MaterialTheme.typography.titleMedium)
        if (stats?.bestPrice != null && stats.cheapestStore != null) {
          val store = stats.cheapestStore
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 4.dp)
              .clickable { onStoreClick(store.id) },
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column {
              Text(store.name, style = MaterialTheme.typography.bodyLarge)
              Text(store.city, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Přepočtená hodnota (X-Display-Currency), když je — jinak originál v měně obchodu
            // (docs/lokalizace.md, "Kurzovní lístek a zobrazovací měna").
            Text(
              rememberMoneyFormatter(stats.bestPriceConverted?.currency ?: stats.bestPriceCurrency)
                .format(stats.bestPriceConverted?.amount ?: stats.bestPrice),
              style = MaterialTheme.typography.bodyLarge,
            )
          }
        } else {
          Text(stringResource(R.string.product_no_price_yet), style = MaterialTheme.typography.bodyMedium)
        }
        Gap()
        HorizontalDivider()
        Gap()

        // --- Ceny po obchodech ---
        Text(stringResource(R.string.product_prices_by_store), style = MaterialTheme.typography.titleMedium)
        if (product.prices.isEmpty()) {
          Text(stringResource(R.string.product_no_price_be_first), style = MaterialTheme.typography.bodyMedium)
        } else {
          Column(modifier = Modifier.padding(top = 8.dp)) {
            product.prices.forEach { price ->
              PriceRow(price, onClick = { onStoreClick(price.store.id) })
            }
          }
        }
        Gap()

        // --- Vaše cena (poslední vlastní zápisy, i dřív než je zpracuje agregace) ---
        if (product.myPrices.isNotEmpty()) {
          HorizontalDivider()
          Gap()
          Text(stringResource(R.string.product_my_price), style = MaterialTheme.typography.titleMedium)
          Column(modifier = Modifier.padding(top = 8.dp)) {
            product.myPrices.forEach { mp ->
              Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("${mp.store.name} — ${priceKindLabel(mp.priceKind)}")
                Text(
                  rememberMoneyFormatter(mp.converted?.currency ?: mp.currency)
                    .format(mp.converted?.amount ?: mp.priceAmount),
                )
              }
              Text(
                formatRelativeDate(mp.observedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          Gap()
        }

        // --- Další informace (odkazy do otevřených databází) ---
        if (product.externalLinks.isNotEmpty()) {
          HorizontalDivider()
          Gap()
          Text(stringResource(R.string.product_more_info), style = MaterialTheme.typography.titleMedium)
          Column(modifier = Modifier.padding(top = 8.dp)) {
            product.externalLinks.forEach { link -> ExternalLinkRow(link, onClick = { openUrl(context, link.url) }) }
          }
          Gap()
        }

        Button(onClick = onWriteObservation, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.product_write_observation))
        }
      }
    }
  }
}

@Composable
private fun PriceRow(price: PriceCurrent, onClick: () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
      Text("${price.store.name} — ${priceKindLabel(price.priceKind)}")
      Text(price.priceAmount?.let { rememberMoneyFormatter(price.currency).format(it) } ?: "–")
    }
    Text(
      stringResource(R.string.product_reported_summary, price.nObs, formatRelativeDate(price.lastObservedAt)),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
  }
}

@Composable
private fun ExternalLinkRow(link: ExternalLink, onClick: () -> Unit) {
  OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    Column {
      Text(link.label)
      // Atribuce zdroje/licence MUSÍ být vidět — ODbL (docs/datovy-model.md).
      Text(link.attribution, style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
private fun Gap() {
  androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
}
