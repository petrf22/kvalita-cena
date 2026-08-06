package cz.kvalitacena.ui.common

import cz.kvalitacena.network.Store
import org.junit.Assert.assertEquals
import org.junit.Test

/** Čistá funkce mimo Compose — stejný vzor jako PriceChartGeometryTest. */
class StoreLabelTest {

  private fun store(name: String, street: String? = null, city: String = "Brno") =
    Store(id = "1", name = name, street = street, city = city, country = "CZ")

  @Test
  fun combinesNameAndCity() {
    assertEquals("Albert Brno-Střed — Brno", storeLabel(store("Albert Brno-Střed")))
  }

  @Test
  fun includesStreetWhenPresent() {
    assertEquals(
      "Potraviny U Kubátů, Náměstí 5 — Znojmo",
      storeLabel(store("Potraviny U Kubátů", street = "Náměstí 5", city = "Znojmo")),
    )
  }

  @Test
  fun blankStreetIsIgnored() {
    assertEquals("Albert — Brno", storeLabel(store("Albert", street = "   ")))
  }

  @Test
  fun blankNameFallsBackToCityOnly() {
    assertEquals("Znojmo", storeLabel(store("  ", city = "Znojmo")))
  }
}
