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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer
import cz.kvalitacena.network.ExternalLink
import cz.kvalitacena.network.PriceCurrent
import cz.kvalitacena.ui.common.QualityBadge
import cz.kvalitacena.ui.common.formatRelativeDate
import cz.kvalitacena.ui.common.openMap
import cz.kvalitacena.ui.common.openUrl
import java.text.NumberFormat
import java.util.Locale

private val PRICE_KIND_LABELS = mapOf(
  "REGULAR" to "Běžná cena",
  "PROMO" to "Akce",
  "CLUB_CARD" to "Klubová karta",
  "CLEARANCE" to "Výprodej",
  "MULTIBUY" to "Multipack",
)

private val CZK_FORMAT: NumberFormat = NumberFormat.getCurrencyInstance(Locale("cs", "CZ"))

/**
 * Detail produktu: název, graf vývoje ceny (rozklikávací rozsah), nejlevnější obchod, odkazy
 * do otevřených databází (Open Food Facts), počet hlášení/cena/datum/obchod po řádcích, obchod
 * klikací na mapu — viz zadání. Hodnocení kvality (1 nejlepší, jako ve škole) vyžaduje přihlášení.
 */
@Composable
fun ProductDetailScreen(
  productId: String,
  onWriteObservation: () -> Unit,
  onNavigateToAccount: () -> Unit,
) {
  val viewModel: ProductDetailViewModel = viewModel(
    factory = viewModelFactory { initializer { ProductDetailViewModel(AppContainer.graphQlClient, productId) } },
  )
  val context = LocalContext.current
  val isLoggedIn = AppContainer.authRepository.accessToken.value != null

  when {
    viewModel.loading -> {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }

    viewModel.notFound -> {
      Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("Tohle zboží se nepodařilo najít.", style = MaterialTheme.typography.bodyLarge)
      }
    }

    else -> {
      val product = viewModel.product!!
      Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(product.name, style = MaterialTheme.typography.headlineSmall)
        val subtitle = listOfNotNull(product.brand?.name, product.category.name).joinToString(" · ")
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        Gap()

        // --- Kvalita ---
        QualityBadge(average = product.quality?.average, count = product.quality?.count ?: 0)
        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          for (grade in 1..5) {
            val selected = product.myQualityRating == grade
            FilterChip(
              selected = selected,
              onClick = {
                if (isLoggedIn) viewModel.rate(grade) else onNavigateToAccount()
              },
              label = { Text(grade.toString()) },
            )
          }
        }
        if (!isLoggedIn) {
          Text(
            "Hodnocení kvality vyžaduje přihlášení — přejdi do záložky Účet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        viewModel.ratingError?.let {
          Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Gap()
        HorizontalDivider()
        Gap()

        // --- Graf vývoje ceny ---
        Text("Vývoj ceny", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          CHART_RANGES.forEach { (days, label) ->
            FilterChip(
              selected = viewModel.selectedDays == days,
              onClick = { viewModel.onDaysChange(days) },
              label = { Text(label) },
            )
          }
        }
        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf("REGULAR", "PROMO").forEach { kind ->
            FilterChip(
              selected = viewModel.selectedPriceKind == kind,
              onClick = { viewModel.onPriceKindChange(kind) },
              label = { Text(PRICE_KIND_LABELS[kind] ?: kind) },
            )
          }
        }
        Gap()
        if (viewModel.historyLoading) {
          Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        } else {
          PriceChart(points = viewModel.history?.points ?: emptyList(), modifier = Modifier.fillMaxWidth())
        }
        Gap()
        HorizontalDivider()
        Gap()

        // --- Nejlevněji ---
        val stats = product.stats
        Text("Nejlevněji", style = MaterialTheme.typography.titleMedium)
        if (stats?.bestPrice != null && stats.cheapestStore != null) {
          val store = stats.cheapestStore
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 4.dp)
              .clickable { openMap(context, store.lat, store.lon, store.name) },
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column {
              Text(store.name, style = MaterialTheme.typography.bodyLarge)
              Text(store.city, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(CZK_FORMAT.format(stats.bestPrice), style = MaterialTheme.typography.bodyLarge)
          }
        } else {
          Text("Zatím tu nikdo cenu nezadal.", style = MaterialTheme.typography.bodyMedium)
        }
        Gap()
        HorizontalDivider()
        Gap()

        // --- Ceny po obchodech ---
        Text("Ceny po obchodech", style = MaterialTheme.typography.titleMedium)
        if (product.prices.isEmpty()) {
          Text("Zatím tu nikdo cenu nezadal — buď první.", style = MaterialTheme.typography.bodyMedium)
        } else {
          Column(modifier = Modifier.padding(top = 8.dp)) {
            product.prices.forEach { price -> PriceRow(price, onClick = { openMap(context, price.store.lat, price.store.lon, price.store.name) }) }
          }
        }
        Gap()

        // --- Další informace (odkazy do otevřených databází) ---
        if (product.externalLinks.isNotEmpty()) {
          HorizontalDivider()
          Gap()
          Text("Další informace", style = MaterialTheme.typography.titleMedium)
          Column(modifier = Modifier.padding(top = 8.dp)) {
            product.externalLinks.forEach { link -> ExternalLinkRow(link, onClick = { openUrl(context, link.url) }) }
          }
          Gap()
        }

        Button(onClick = onWriteObservation, modifier = Modifier.fillMaxWidth()) {
          Text("Zapsat cenu")
        }
      }
    }
  }
}

@Composable
private fun PriceRow(price: PriceCurrent, onClick: () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
      Text("${price.store.name} — ${PRICE_KIND_LABELS[price.priceKind] ?: price.priceKind}")
      Text(price.priceAmount?.let { CZK_FORMAT.format(it) } ?: "–")
    }
    Text(
      "${price.nObs}× hlášeno · naposledy ${formatRelativeDate(price.lastObservedAt)}",
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
