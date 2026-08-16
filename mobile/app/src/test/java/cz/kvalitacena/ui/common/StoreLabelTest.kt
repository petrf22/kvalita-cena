package cz.kvalitacena.ui.common

import cz.kvalitacena.network.Store
import org.junit.Assert.assertEquals
import org.junit.Test

/** Čistá funkce mimo Compose — stejný vzor jako PriceChartGeometryTest. */
class StoreLabelTest {

  private fun store(name: String, street: String? = null, city: String = "Brno", country: String = "CZ") =
    Store(id = "1", name = name, street = street, city = city, country = country)

  @Test
  fun combinesNameAndCity() {
    assertEquals("Albert Brno-Střed — Brno", storeLabel(store("Albert Brno-Střed"), homeCountry = "CZ"))
  }

  @Test
  fun includesStreetWhenPresent() {
    assertEquals(
      "Potraviny U Kubátů, Náměstí 5 — Znojmo",
      storeLabel(store("Potraviny U Kubátů", street = "Náměstí 5", city = "Znojmo"), homeCountry = "CZ"),
    )
  }

  @Test
  fun blankStreetIsIgnored() {
    assertEquals("Albert — Brno", storeLabel(store("Albert", street = "   "), homeCountry = "CZ"))
  }

  @Test
  fun blankNameFallsBackToCityOnly() {
    assertEquals("Znojmo", storeLabel(store("  ", city = "Znojmo"), homeCountry = "CZ"))
  }

  @Test
  fun hidesCountryWhenItMatchesHomeCountry() {
    assertEquals("Lidl — Brno", storeLabel(store("Lidl", country = "CZ"), homeCountry = "CZ"))
  }

  @Test
  fun appendsCountryWhenItDiffersFromHomeCountry() {
    assertEquals(
      "Lidl — Bratislava (SK)",
      storeLabel(store("Lidl", city = "Bratislava", country = "SK"), homeCountry = "CZ"),
    )
  }

  @Test
  fun showsCountryWhenHomeCountryIsUnknown() {
    assertEquals(
      "Lidl — Bratislava (SK)",
      storeLabel(store("Lidl", city = "Bratislava", country = "SK"), homeCountry = null),
    )
  }
}
