package cz.kvalitacena.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val PREFS_NAME = "kvalita_a_cena_settings"
private const val KEY_COUNTRY = "country"

/** Jen výchozí hádanka, než appka stáhne countries()/uživatel zemi sám nastaví — nikdy zdroj pravdy. */
private val LANG_TO_COUNTRY = mapOf("cs" to "CZ", "sk" to "SK", "pl" to "PL", "en" to "CZ")

/**
 * Přepínač země nezávislý na jazyku (docs/lokalizace.md, "Country selector v UI") — mobilní
 * protějšek frontend `services/country-service.ts`. Preference je vždy autoritativně na
 * klientovi (SharedPreferences), appka se z ní NIKDY nestahuje zpátky ze serveru (motivační
 * případ dokumentu: Čech žijící v Polsku chce české UI a polské ceny). Push na server
 * (`setLocale`, jen pro asynchronní OTP e-mail) dělá volající po přihlášení
 * ([cz.kvalitacena.ui.settings.SettingsScreen]) — tahle třída jen drží lokální stav, stejné
 * rozdělení zodpovědnosti jako u [DisplayCurrencyStore] (appka měnu/zemi nikde v OS nezná).
 *
 * Ovlivňuje výchozí zemi/měnu formuláře zakládání obchodu a filtr `country` v hledání. NEMĚNÍ
 * měnu už zapsaných cen ani existujících obchodů — ta je vlastností konkrétní provozovny
 * (`CurrencyResolver.forStore` na backendu).
 */
class CountryStore(context: Context) {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  var country: String by mutableStateOf(
    prefs.getString(KEY_COUNTRY, null) ?: LANG_TO_COUNTRY[currentLangTag()] ?: "CZ",
  )
    private set

  fun select(value: String) {
    if (value == country) return
    country = value
    prefs.edit().putString(KEY_COUNTRY, value).apply()
  }
}
