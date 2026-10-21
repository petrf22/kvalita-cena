package cz.kvalitacena.ui.settings

import android.content.Context
import android.content.SharedPreferences
import cz.kvalitacena.network.Store
import kotlinx.serialization.json.Json

private const val PREFS_NAME = "kvalita_a_cena_settings"
private const val KEY_STORE_ID = "last_price_store_id"
private const val KEY_STORE_JSON = "last_price_store_json"
private const val KEY_SAVED_AT = "last_price_store_saved_at"
private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

internal fun rememberedStoreId(id: String?, savedAt: Long, now: Long): String? =
  id?.takeIf { savedAt > 0 && now - savedAt <= MAX_AGE_MS }

/**
 * Poslední obchod použitý při zápisu ceny. Po 30 dnech se zapomene, aby appka nenabízela dávno
 * nerelevantní provozovnu.
 *
 * **Ukládá se celý obchod, ne jen jeho id** (od 2026-09) — s pouhým id musela appka detail vždy
 * dotáhnout ze serveru (`storeById`), takže v obchodě bez signálu nebyl k dispozici ani
 * zapamatovaný obchod. A offline je přesně ten stav, kdy ho člověk potřebuje nejvíc: ceny se
 * zapisují v regálu, kde signál často není (kořenový CLAUDE.md, `docs/design.md`, čtvrtý stav
 * obrazovky). Uložený snapshot je jen pro předvýběr; čerstvá data si obrazovka dotáhne, když
 * může.
 */
class LastStoreStore(
  context: Context,
  private val now: () -> Long = System::currentTimeMillis,
) {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val json = Json { ignoreUnknownKeys = true }

  fun rememberedId(): String? {
    val id = prefs.getString(KEY_STORE_ID, null)
    val savedAt = prefs.getLong(KEY_SAVED_AT, 0)
    val remembered = rememberedStoreId(id, savedAt, now())
    if (id != null && remembered == null) {
      clear()
    }
    return remembered
  }

  /** Snapshot bez jediného síťového dotazu — `null`, když nic uloženého není, nebo je prošlé. */
  fun remembered(): Store? {
    if (rememberedId() == null) return null
    val stored = prefs.getString(KEY_STORE_JSON, null) ?: return null
    // Starší verze appky ukládala jen id, a formát DTO se může změnit — poškozený snapshot se
    // tiše zahodí, zbytek výběru obchodu na něm nestojí.
    return runCatching { json.decodeFromString<Store>(stored) }.getOrNull()
  }

  fun remember(store: Store) {
    prefs.edit()
      .putString(KEY_STORE_ID, store.id)
      .putString(KEY_STORE_JSON, json.encodeToString(store))
      .putLong(KEY_SAVED_AT, now())
      .apply()
  }

  fun clear() {
    prefs.edit().remove(KEY_STORE_ID).remove(KEY_STORE_JSON).remove(KEY_SAVED_AT).apply()
  }
}
