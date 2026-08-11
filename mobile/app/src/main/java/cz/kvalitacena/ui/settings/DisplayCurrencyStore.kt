package cz.kvalitacena.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val PREFS_NAME = "kvalita_a_cena_settings"
private const val KEY_CURRENCY = "display_currency"

/** Appka nabízí přepočet jen do měn, které umí stáhnout z ČNB, + CZK jako pivot (docs/lokalizace.md). */
enum class DisplayCurrency(val code: String) {
  CZK("CZK"),
  EUR("EUR"),
  PLN("PLN"),
  USD("USD");

  companion object {
    fun fromCode(code: String?): DisplayCurrency? = entries.firstOrNull { it.code == code }
  }
}

/**
 * Zobrazovací měna napříč appkou (docs/lokalizace.md, "Kurzovní lístek a zobrazovací měna") —
 * `null` = "měna obchodu" (výchozí), appka pak vůbec neposílá X-Display-Currency a nic
 * nepřepočítává ([cz.kvalitacena.network.DisplayCurrencyInterceptor]). Obyčejné
 * SharedPreferences, ne EncryptedSharedPreferences jako [cz.kvalitacena.auth.TokenStore] —
 * není to tajemství.
 *
 * <p>Na rozdíl od [LocaleController] (deleguje na `AppCompatDelegate`/systémový `LocaleManager`)
 * appka měnu nikde v OS nezná, proto vlastní `mutableStateOf` — jediný způsob, jak Compose
 * uvidí změnu bez restartu Activity.
 */
class DisplayCurrencyStore(context: Context) {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  var currency: DisplayCurrency? by mutableStateOf(DisplayCurrency.fromCode(prefs.getString(KEY_CURRENCY, null)))
    private set

  fun select(value: DisplayCurrency?) {
    currency = value
    prefs.edit().apply {
      if (value == null) remove(KEY_CURRENCY) else putString(KEY_CURRENCY, value.code)
    }.apply()
  }
}
