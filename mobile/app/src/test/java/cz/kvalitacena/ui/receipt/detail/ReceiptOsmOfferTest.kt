package cz.kvalitacena.ui.receipt.detail

import cz.kvalitacena.network.OsmStoreCandidate
import cz.kvalitacena.network.OsmStoreCandidateResult
import cz.kvalitacena.ui.common.NearbyOsmStore
import cz.kvalitacena.ui.common.OsmState
import cz.kvalitacena.ui.common.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptOsmOfferTest {

  private fun candidate(name: String, distance: Double, lat: Double = 50.0, lon: Double = 14.0) = OsmStoreCandidate(
    name = name,
    lat = lat,
    lon = lon,
    osmRef = "node/$name",
    distanceMeters = distance,
  )

  @Test
  fun samaSeNacteDokudSeNeptalo() {
    assertTrue(shouldAutoLoadOsmAtAddress(OsmState.Idle, hasAddress = true))
  }

  @Test
  fun samaSeNacteZnovuPoNavratuPripojeni() {
    assertTrue(shouldAutoLoadOsmAtAddress(OsmState.Offline, hasAddress = true))
  }

  @Test
  fun bezMestaNaUctenceSeNeptaVubec() {
    assertFalse(shouldAutoLoadOsmAtAddress(OsmState.Idle, hasAddress = false))
    assertFalse(shouldAutoLoadOsmAtAddress(OsmState.Offline, hasAddress = false))
  }

  @Test
  fun poVysledkuVypadkuNeboChybeUzJenTlacitkem() {
    assertFalse(shouldAutoLoadOsmAtAddress(OsmState.Loading, hasAddress = true))
    assertFalse(shouldAutoLoadOsmAtAddress(OsmState.Loaded(emptyList(), "© OpenStreetMap"), hasAddress = true))
    assertFalse(shouldAutoLoadOsmAtAddress(OsmState.Unavailable, hasAddress = true))
    assertFalse(shouldAutoLoadOsmAtAddress(OsmState.Failed(UiText.Raw("x")), hasAddress = true))
  }

  @Test
  fun vypadekNeniPrazdnyVysledek() {
    assertEquals(
      OsmState.Unavailable,
      osmStateAtAddress(OsmStoreCandidateResult(status = "UNAVAILABLE", attribution = "© OpenStreetMap")),
    )
  }

  @Test
  fun vypadekPrebijeIPripadneKandidaty() {
    assertEquals(
      OsmState.Unavailable,
      osmStateAtAddress(
        OsmStoreCandidateResult(status = "UNAVAILABLE", candidates = listOf(candidate("A", 10.0)), attribution = "©"),
      ),
    )
  }

  @Test
  fun neznamaAdresaJePrazdnyVysledek() {
    assertEquals(
      OsmState.Loaded(emptyList(), "© OpenStreetMap"),
      osmStateAtAddress(OsmStoreCandidateResult(status = "OK", attribution = "© OpenStreetMap")),
    )
  }

  @Test
  fun poradiAVzdalenostZustavajiZeServeru() {
    // Server dává podobné názvu z účtenky nahoru, i když jsou dál — klient nepřeřazuje podle
    // vzdálenosti a nepřepočítává ji z polohy telefonu: je to vzdálenost od adresy.
    val similar = candidate("Albert", distance = 240.0)
    val other = candidate("Trafika", distance = 30.0)
    assertEquals(
      OsmState.Loaded(listOf(NearbyOsmStore(similar, 240.0), NearbyOsmStore(other, 30.0)), "©"),
      osmStateAtAddress(OsmStoreCandidateResult(status = "OK", candidates = listOf(similar, other), attribution = "©")),
    )
  }
}
