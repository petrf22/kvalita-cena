package cz.kvalitacena.receipt

import cz.kvalitacena.network.ReceiptLine
import cz.kvalitacena.network.RetailChain
import cz.kvalitacena.network.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptLoyaltyCardTest {

  private fun store(id: String, chain: RetailChain? = null) =
    Store(id = id, chain = chain, name = "Obchod", city = "Brno", country = "CZ")

  private fun line(discount: Double?) = ReceiptLine(id = "1", lineNo = 1, labelRaw = "X", status = "UNMATCHED", discountAmount = discount)

  @Test
  fun kartaPatriRetezciSamostatnaProdejnaMaVlastniKlic() {
    assertEquals("chain:3", loyaltyCardKey(store("7", RetailChain("3", "Billa", "CHAIN"))))
    assertEquals("store:7", loyaltyCardKey(store("7")))
    assertNull(loyaltyCardKey(null))
  }

  @Test
  fun zaskrtnutiJenUUctenkySeSlevou() {
    assertFalse(hasDiscountLine(listOf(line(null), line(0.0))))
    assertTrue(hasDiscountLine(listOf(line(null), line(5.0))))
  }
}
