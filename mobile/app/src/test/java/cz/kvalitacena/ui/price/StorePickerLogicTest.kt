package cz.kvalitacena.ui.price

import cz.kvalitacena.network.OsmStoreCandidate
import cz.kvalitacena.network.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ME_LAT = 49.19960
private const val ME_LON = 16.60890

class StorePickerLogicTest {

  private fun store(id: String, name: String, lat: Double?, lon: Double?, city: String = "Brno") =
    Store(id = id, name = name, city = city, country = "CZ", lat = lat, lon = lon)

  private fun candidate(name: String, lat: Double, lon: Double, city: String? = "Brno", osmRef: String = "way/1") =
    OsmStoreCandidate(name = name, city = city, lat = lat, lon = lon, osmRef = osmRef)

  @Test
  fun obchodyJsouSerazeneOdNejblizsiho() {
    val blizky = store("1", "Blízký", 49.1997, 16.6090)
    val vzdaleny = store("2", "Vzdálený", 49.2050, 16.6150)

    val result = nearbyStoresWithin(listOf(vzdaleny, blizky), ME_LAT, ME_LON, 5000)

    assertEquals(listOf("1", "2"), result.map { it.store.id })
    assertTrue(result[0].distanceMeters < result[1].distanceMeters)
  }

  @Test
  fun obchodZaOkruhemVypadne() {
    // Server hledá s okruhem zvětšeným o chybu zaokrouhlení polohy, takže pár řádků navíc
    // dorazí vždycky — dofiltrovat je umí jen klient, který zná přesnou polohu.
    val zaHranici = store("2", "Za hranicí", 49.2100, 16.6200)

    val result = nearbyStoresWithin(listOf(zaHranici), ME_LAT, ME_LON, 200)

    assertTrue(result.isEmpty())
  }

  @Test
  fun obchodBezSouradnicSeDoOkoliNepocita() {
    val bezSouradnic = store("3", "Bez souřadnic", null, null)

    assertTrue(nearbyStoresWithin(listOf(bezSouradnic), ME_LAT, ME_LON, 5000).isEmpty())
  }

  @Test
  fun kandidatKteryVKatalogUZJeSeVNabidceNeobjevi() {
    val vlastni = store("1", "Žabka", 49.1997, 16.6090)
    val kandidati = nearbyOsmStoresWithin(
      listOf(candidate("ŽABKA", 49.1997, 16.6090), candidate("Farma Sedlák", 49.1998, 16.6091, osmRef = "way/2")),
      ME_LAT, ME_LON, 1000,
    )

    val result = osmCandidatesNotInOwnCatalog(kandidati, listOf(vlastni))

    assertEquals(listOf("Farma Sedlák"), result.map { it.candidate.name })
  }

  @Test
  fun zapamatovanyObchodSePredvybereJenKdyzJeVOkoli() {
    val zapamatovany = store("1", "Lidl", 49.1997, 16.6090)
    val jiny = store("2", "Albert", 49.1998, 16.6091)
    val okoli = nearbyStoresWithin(listOf(jiny), ME_LAT, ME_LON, 1000)

    assertNull(storeToPreselect(zapamatovany, okoli, locationKnown = true))
    assertEquals(
      zapamatovany.id,
      storeToPreselect(zapamatovany, nearbyStoresWithin(listOf(zapamatovany, jiny), ME_LAT, ME_LON, 1000), true)?.id,
    )
  }

  @Test
  fun bezPolohyZapamatovanyObchodVyhraje() {
    // Zpětný zápis z domova a zápis v obchodě bez signálu — bez polohy neexistuje žádný důkaz,
    // že je uživatel jinde, takže mu appka nesmí zapamatovaný obchod sebrat.
    val zapamatovany = store("1", "Lidl", 49.1997, 16.6090)

    assertEquals(zapamatovany.id, storeToPreselect(zapamatovany, emptyList(), locationKnown = false)?.id)
  }

  @Test
  fun bezZapamatovanehoObchoduSeNicNepredvybere() {
    assertNull(storeToPreselect(null, emptyList(), locationKnown = false))
    assertNull(storeToPreselect(null, emptyList(), locationKnown = true))
  }

  // --- výběr obchodu bez signálu (price/StoreCache) ---

  private fun cached(id: String, name: String, city: String, lat: Double? = null, lon: Double? = null, street: String? = null) =
    Store(id = id, name = name, city = city, country = "CZ", lat = lat, lon = lon, street = street)

  @Test
  fun offlineNabidkaRadiPodleVzdalenostiAObchodyBezSouradnicNechavaNaKonec() {
    val stores = listOf(
      cached("daleko", "Daleký", "Brno", 49.30, 16.60),
      cached("bez", "Bez souřadnic", "Brno"),
      cached("blizko", "Blízký", "Brno", 49.2001, 16.6090),
    )
    assertEquals(listOf("blizko", "daleko", "bez"), offlineStoreSuggestions(stores, 49.2000, 16.6089).map { it.id })
  }

  @Test
  fun offlineNabidkaBezPolohyDrziPoradiCache() {
    val stores = (1..OFFLINE_STORE_SUGGESTIONS + 5).map { cached("s$it", "Obchod $it", "Brno", 49.0, 16.0) }
    val offered = offlineStoreSuggestions(stores, null, null)
    assertEquals(OFFLINE_STORE_SUGGESTIONS, offered.size)
    assertEquals("s1", offered.first().id)
  }

  @Test
  fun offlineHledaniBereVsechnaSlovaBezOhleduNaVelikostPismen() {
    val stores = listOf(
      cached("a", "Albert", "Brno", street = "Nádražní 1"),
      cached("b", "Albert", "Praha"),
      cached("c", "Billa", "Brno"),
    )
    assertEquals(listOf("a"), filterCachedStores(stores, "albert BRNO").map { it.id })
    assertEquals(listOf("a"), filterCachedStores(stores, "nádražní").map { it.id })
    assertEquals(emptyList<String>(), filterCachedStores(stores, "   ").map { it.id })
  }

  @Test
  fun bezSiteSeZnamouPolohouSeVzdalenyZapamatovanyObchodNepredvybere() {
    val brno = cached("brno", "Albert Brno", "Brno", 49.1951, 16.6068)
    // Telefon stojí v Praze, zapamatovaný obchod je v Brně.
    assertNull(storeToPreselectOffline(brno, 50.0752, 14.4410, 500))
    // Stojí u něj — předvybere se.
    assertEquals("brno", storeToPreselectOffline(brno, 49.1952, 16.6069, 500)?.id)
  }

  @Test
  fun bezSiteSeObchodBezSouradnicPredvybereProtozeHoNejdeVyvratit() {
    val unknown = cached("x", "Obchod bez GPS", "Brno")
    assertEquals("x", storeToPreselectOffline(unknown, 50.0752, 14.4410, 500)?.id)
    assertNull(storeToPreselectOffline(null, 50.0752, 14.4410, 500))
  }
}
