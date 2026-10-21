package cz.kvalitacena.ui.price

import cz.kvalitacena.network.OsmStoreCandidate
import cz.kvalitacena.network.Store
import cz.kvalitacena.ui.common.NearbyOsmStore
import cz.kvalitacena.ui.common.NearbyStore
import cz.kvalitacena.ui.common.distanceMeters
import cz.kvalitacena.ui.common.isWithin
import java.text.Normalizer
import java.util.Locale

/**
 * Seřadí obchody podle skutečné vzdálenosti a zahodí ty, které se do nastaveného okruhu ve
 * skutečnosti nevejdou. Server totiž hledá s okruhem zvětšeným o chybu zaokrouhlení polohy
 * (StoreGraphQlController.ROUNDING_SLACK_M), aby uživateli nevypadl obchod, ve kterém právě
 * stojí — pár řádků navíc proto odfiltruje až klient.
 *
 * Obchod bez souřadnic se zahazuje — vzdálenost k němu neznáme, takže "v okolí" není. Výsledky
 * našeptávače (kde je obchod bez GPS legitimní, docs/datovy-model.md) sem proto nepatří.
 */
fun nearbyStoresWithin(stores: List<Store>, lat: Double, lon: Double, radiusMeters: Int): List<NearbyStore> =
  stores.mapNotNull { store ->
    val storeLat = store.lat
    val storeLon = store.lon
    if (storeLat == null || storeLon == null) return@mapNotNull null
    NearbyStore(store, distanceMeters(lat, lon, storeLat, storeLon))
  }
    .filter { isWithin(it.distanceMeters, radiusMeters) }
    .sortedBy { it.distanceMeters }

/** Totéž pro nabídku z OpenStreetMap — vzdálenost ze serveru se přepočítá z přesné polohy. */
fun nearbyOsmStoresWithin(
  candidates: List<OsmStoreCandidate>,
  lat: Double,
  lon: Double,
  radiusMeters: Int,
): List<NearbyOsmStore> =
  candidates.map { NearbyOsmStore(it, distanceMeters(lat, lon, it.lat, it.lon)) }
    .filter { isWithin(it.distanceMeters, radiusMeters) }
    .sortedBy { it.distanceMeters }

/**
 * Druhá síť proti duplicitám — hlavní filtr běží na serveru (OsmStoreCandidateFilter), ale
 * nabídka z OSM může být z jiného okruhu nebo staršího dotazu než seznam vlastních obchodů.
 * Porovnává se zjednodušená normalizovaná identita (název + město + ulice), tedy klíč
 * `uq_store_identity` — bez ulice by jedna pobočka řetězce v katalogu schovala všechny ostatní
 * ve stejném městě. Země se porovná jen u kandidáta, který ji má (`addr:country` v OSM často
 * chybí a fallback, který dosazuje server, klient nezná). `osmRef` mobilní [Store] nenese,
 * shodu podle něj řeší server.
 */
fun osmCandidatesNotInOwnCatalog(
  candidates: List<NearbyOsmStore>,
  ownStores: List<Store>,
): List<NearbyOsmStore> {
  if (candidates.isEmpty() || ownStores.isEmpty()) return candidates
  return candidates.filterNot { nearby ->
    val candidate = nearby.candidate
    val key = identity(candidate.name, candidate.city.orEmpty(), candidate.street)
    ownStores.any { store ->
      identity(store.name, store.city, store.street) == key &&
        (candidate.country == null || candidate.country.equals(store.country, ignoreCase = true))
    }
  }
}

/**
 * Který obchod se má předvybrat. Zapamatovaný obchod (LastStoreStore, 30 dní) vyhraje jen
 * tehdy, když je mezi nalezenými v okolí — jinak by appka člověku, který je prokazatelně
 * jinde, nabídla obchod z minulého týdne a on by k němu omylem zapsal cenu.
 *
 * Když ale poloha vůbec není k dispozici ([locationKnown] = false: nepovolená, vypnutá, nebo
 * offline), žádný důkaz "je jinde" neexistuje — pak se zapamatovaný obchod předvybere jako
 * dřív, což je přesně scénář zpětného zápisu z domova a zápisu v obchodě bez signálu.
 */
fun storeToPreselect(remembered: Store?, nearby: List<NearbyStore>, locationKnown: Boolean): Store? {
  if (remembered == null) return null
  if (!locationKnown) return remembered
  return nearby.firstOrNull { it.store.id == remembered.id }?.store
}

private fun identity(name: String, city: String, street: String?): String =
  "${norm(name)}|${norm(city)}|${norm(street.orEmpty())}"

/** Zjednodušená obdoba `core.norm_text` — malá písmena, bez diakritiky, sjednocené mezery. */
private fun norm(value: String): String =
  Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    .lowercase(Locale.ROOT)
    .replace(Regex("\\s+"), " ")
    .trim()
