package cz.kvalitacena.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.text.NumberFormat
import java.util.Currency

/**
 * Formátovač měny podle aktuálního jazyka appky a měny z dat (docs/lokalizace.md) — nahrazuje
 * dřívější top-level `NumberFormat.getCurrencyInstance(Locale("cs", "CZ"))`, který měl natvrdo
 * obojí a inicializoval se jednou za proces (SearchScreen.kt/ProductDetailScreen.kt), takže by
 * nereagoval ani na změnu jazyka. `currency` chybí jen tehdy, když chybí i cena samotná (viz
 * `PriceCurrent.currency`/`ProductStats.bestPriceCurrency` v `network/Dto.kt`) — CZK je tu jen
 * nouzový fallback pro ten okrajový případ, ne výchozí měna appky.
 */
@Composable
fun rememberMoneyFormatter(currency: String?): NumberFormat {
  val locale = LocalConfiguration.current.locales[0]
  return remember(locale, currency) {
    NumberFormat.getCurrencyInstance(locale).apply {
      this.currency = Currency.getInstance(currency ?: "CZK")
    }
  }
}
