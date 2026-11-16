package cz.kvalitacena.price

import cz.kvalitacena.network.Store
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
private data class CachedStore(val store: Store, val seenAt: Long)

/**
 * Obchody, které zařízení už vidělo (nabídka v okolí, ručně vybrané) — aby šel obchod vybrat
 * i bez signálu, kdy `nearbyStores` ani našeptávač neodpoví. `LastStoreStore` drží jen jeden
 * naposledy použitý; kdo střídá dva obchody, by v tom druhém offline nevybral nic.
 *
 * Do zařízení se ukládá jen to, co server o obchodech veřejně vrací — žádná poloha uživatele
 * (docs/soukromi.md). Vzdálenost a pořadí počítá obrazovka z aktuální polohy
 * (`ui/common/GeoDistance.kt`), GPS funguje i bez sítě.
 */
class StoreCache(
  filesDir: File,
  private val now: () -> Long = System::currentTimeMillis,
) {

  private val file = File(filesDir, "stores-seen.json")
  private val json = Json { ignoreUnknownKeys = true }
  private val serializer = ListSerializer(CachedStore.serializer())

  /** Naposledy viděné první. */
  @Synchronized
  fun all(): List<Store> = load().map { it.store }

  /** Přidá nebo obnoví obchody; drží [MAX_STORES] naposledy viděných, starší než [MAX_AGE_MS] zahodí. */
  @Synchronized
  fun remember(stores: List<Store>) {
    if (stores.isEmpty()) return
    val timestamp = now()
    val fresh = stores.associateBy { it.id }
    val merged = fresh.values.map { CachedStore(it, timestamp) } +
      load().filter { it.store.id !in fresh }
    val kept = merged.filter { timestamp - it.seenAt <= MAX_AGE_MS }.take(MAX_STORES)
    file.parentFile?.mkdirs()
    file.writeText(json.encodeToString(serializer, kept))
  }

  @Synchronized
  fun clear() {
    file.delete()
  }

  // Poškozený nebo starým formátem zapsaný soubor se tiše zahodí — je to jen cache.
  private fun load(): List<CachedStore> =
    runCatching { json.decodeFromString(serializer, file.readText()) }
      .getOrDefault(emptyList())
      .sortedByDescending { it.seenAt }

  companion object {
    const val MAX_STORES = 200
    const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000
  }
}
