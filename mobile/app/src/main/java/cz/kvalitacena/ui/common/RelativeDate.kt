package cz.kvalitacena.ui.common

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import cz.kvalitacena.R
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "před 3 dny" místo syrového ISO data — pro seznam hledání a detail produktu. `@Composable`
 * schválně (docs/lokalizace.md, mobilní protějšek `shared/relative-date.ts`/`RelativeTimeFormat`
 * na webu): jediný způsob, jak se ke stringu "zatím nikdy" dostat lokalizovaně, je
 * `stringResource` z Compose kontextu, oba volající místa (SearchScreen/ProductDetailScreen)
 * ho už mají. `DateUtils.getRelativeTimeSpanString` řeší plurály i "včera" v jazyce appky
 * (cs/sk/en/pl) sama — appka je nemusí ručně skládat jako dřív.
 */
@Composable
fun formatRelativeDate(iso: String?): String {
  if (iso == null) return stringResource(R.string.common_never)
  return try {
    val time = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
      .toString()
  } catch (e: Exception) {
    iso
  }
}

/**
 * Krátké datum ("17. 8. 2026") podle jazyka appky — pro popisky grafu vývoje ceny (`PriceChart`),
 * kde `agg.price_daily.day` chodí jako holé ISO `yyyy-MM-dd` bez formátování na serveru
 * (docs/lokalizace.md). `LocalConfiguration.current.locales[0]`, ne `Locale.getDefault()` —
 * stejný důvod jako `Money.kt`.
 */
@Composable
fun formatShortDate(day: String): String {
  val locale = LocalConfiguration.current.locales[0]
  val formatter = remember(locale) {
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
  }
  return try {
    LocalDate.parse(day).format(formatter)
  } catch (e: Exception) {
    day
  }
}
