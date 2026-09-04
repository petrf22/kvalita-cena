package cz.kvalitacena.ui.settings

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "kvalita_a_cena_settings"
private const val KEY_STORE_ID = "last_price_store_id"
private const val KEY_SAVED_AT = "last_price_store_saved_at"
private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

internal fun rememberedStoreId(id: String?, savedAt: Long, now: Long): String? =
  id?.takeIf { savedAt > 0 && now - savedAt <= MAX_AGE_MS }

/**
 * Poslední obchod použitý při zápisu ceny. Po 30 dnech se zapomene, aby appka nenabízela
 * dávno nerelevantní provozovnu. Ukládá se jen id, detail se vždy znovu načte ze serveru.
 */
class LastStoreStore(
  context: Context,
  private val now: () -> Long = System::currentTimeMillis,
) {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun rememberedId(): String? {
    val id = prefs.getString(KEY_STORE_ID, null)
    val savedAt = prefs.getLong(KEY_SAVED_AT, 0)
    val remembered = rememberedStoreId(id, savedAt, now())
    if (id != null && remembered == null) {
      clear()
    }
    return remembered
  }

  fun remember(id: String) {
    prefs.edit().putString(KEY_STORE_ID, id).putLong(KEY_SAVED_AT, now()).apply()
  }

  fun clear() {
    prefs.edit().remove(KEY_STORE_ID).remove(KEY_SAVED_AT).apply()
  }
}
