package cz.kvalitacena.ui.common

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cz.kvalitacena.R
import java.time.OffsetDateTime

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
