package cz.kvalitacena.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cz.kvalitacena.network.CountryInfo

private const val PREFS_NAME = "kvalita_a_cena_settings"
private const val KEY_COUNTRY = "country"
private const val KEY_COUNTRY_LOCALE = "country_locale"

/** Jen výchozí hádanka, než appka stáhne countries()/uživatel zemi sám nastaví — nikdy zdroj pravdy. */
private val LANG_TO_COUNTRY = mapOf("cs" to "CZ", "sk" to "SK", "pl" to "PL", "en" to "CZ", "de" to "DE")

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

  /**
   * Výchozí jazyk zvolené země (`CountryInfo.defaultLocale`, tedy `app.i18n.country-locale` na
   * serveru) — appka ho NIKDY nepoužije k volbě jazyka UI (ta je čistě na klientovi), jen jako
   * nápovědu, který další jazyk nabídnout u názvu zboží (ProductFormViewModel.countryLang).
   * Drží se v prefs, aby ho formulář měl hned po startu, ještě než [refresh] doběhne; null
   * znamená „appka číselník zemí zatím nestáhla".
   */
  var countryLocale: String? by mutableStateOf(prefs.getString(KEY_COUNTRY_LOCALE, null))
    private set

  fun select(value: String) {
    if (value == country) return
    country = value
    prefs.edit().putString(KEY_COUNTRY, value).apply()
    // Jazyk staré země by po přepnutí platil dál, dokud se číselník nestáhne znovu — radši nic
    // než nabídnout u slovenského nákupu polštinu.
    updateLocale(null)
  }

  /**
   * Dosadí jazyk zvolené země ze staženého číselníku. Volá se odkudkoli, kde appka `countries()`
   * načte (Nastavení, formulář zboží) — výpadek dotazu se ignoruje, poslední známá hodnota
   * v prefs zůstane platit.
   */
  fun applyCountries(countries: List<CountryInfo>) {
    updateLocale(countries.find { it.code == country }?.defaultLocale)
  }

  private fun updateLocale(value: String?) {
    if (value == countryLocale) return
    countryLocale = value
    prefs.edit().putString(KEY_COUNTRY_LOCALE, value).apply()
  }
}
