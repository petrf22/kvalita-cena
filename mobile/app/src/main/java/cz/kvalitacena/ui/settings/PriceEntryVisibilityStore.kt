package cz.kvalitacena.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val PREFS_NAME = "kvalita_a_cena_settings"
private const val KEY_EXPANDED = "price_entry_expanded"

/**
 * Jestli se sekce "Zadat cenu" na PriceEntryScreen zobrazuje rovnou po skenu, nebo je schovaná
 * za tlačítkem — appka slouží dvěma skupinám lidí (kdo jen hledá ceny poblíž, kdo je zapisuje).
 * Výchozí `false` (schováno); jakmile uživatel jednou úspěšně zapíše cenu, appka usoudí, že je
 * "zapisovač" a příště rovnou rozbalí ([cz.kvalitacena.ui.price.PriceEntryViewModel]). Rozbalení
 * jen pro AKTUÁLNÍ obrazovku (klik na tlačítko "Zadat cenu" bez úspěšného odeslání) se
 * NEUKLÁDÁ — jen sbalení nebo úspěšný zápis mění tuhle perzistentní hodnotu. Stejný vzor jako
 * [CountryStore]/[DisplayCurrencyStore] — obyčejné SharedPreferences, `mutableStateOf`, aby to
 * Compose viděl bez restartu Activity.
 */
class PriceEntryVisibilityStore(context: Context) {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  var expandedByDefault: Boolean by mutableStateOf(prefs.getBoolean(KEY_EXPANDED, false))
    private set

  fun select(value: Boolean) {
    if (value == expandedByDefault) return
    expandedByDefault = value
    prefs.edit().putBoolean(KEY_EXPANDED, value).apply()
  }
}
