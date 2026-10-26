package cz.kvalitacena.ui.receipt

import cz.kvalitacena.network.Receipt
import cz.kvalitacena.network.ReceiptLineCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptStorePrefillTest {

  private fun receipt(name: String? = null, address: String? = null, ico: String? = null) = Receipt(
    id = "1",
    processingStatus = "PROCESSED",
    reviewStatus = "PENDING",
    createdAt = "2026-10-26T10:00:00Z",
    storeNameRaw = name,
    storeAddressRaw = address,
    storeIcoRaw = ico,
    lineCounts = ReceiptLineCounts(total = 0, unmatched = 0, suggested = 0, matched = 0, ignored = 0),
  )

  @Test
  fun uliceAPscSMestem() {
    assertEquals(ReceiptAddress("Nádražní 1", "100 00", "Vzorov"), splitReceiptAddress("Nádražní 1, 100 00 Vzorov"))
  }

  @Test
  fun pscBezMezery() {
    assertEquals(ReceiptAddress("Nádražní 1", "10000", "Vzorov"), splitReceiptAddress("Nádražní 1, 10000 Vzorov"))
  }

  @Test
  fun jenPscSMestem() {
    assertEquals(ReceiptAddress(null, "100 00", "Praha 10"), splitReceiptAddress("100 00 Praha 10"))
  }

  @Test
  fun jenUliceKdyzParserPscNenasel() {
    assertEquals(ReceiptAddress("Nádražní 1", null, null), splitReceiptAddress("Nádražní 1"))
  }

  @Test
  fun carkaVUliciSeNerozdeli() {
    assertEquals(
      ReceiptAddress("OC Galerie, Hlavní 5", "602 00", "Brno"),
      splitReceiptAddress("OC Galerie, Hlavní 5, 602 00 Brno"),
    )
  }

  @Test
  fun bezPscZustaneCelyTextVUlici() {
    assertEquals(ReceiptAddress("OC Galerie, Hlavní 5", null, null), splitReceiptAddress("OC Galerie, Hlavní 5"))
  }

  @Test
  fun prazdnaAdresa() {
    assertEquals(ReceiptAddress(null, null, null), splitReceiptAddress(null))
    assertEquals(ReceiptAddress(null, null, null), splitReceiptAddress("  "))
  }

  @Test
  fun predvyplneniZHlavicky() {
    assertEquals(
      StorePrefill("albert", "Nádražní 1", "100 00", "Vzorov", "12345678"),
      receiptStorePrefill(receipt(" albert ", "Nádražní 1, 100 00 Vzorov", "12345678")),
    )
  }

  @Test
  fun bezHlavickyNeniCimPredvyplnit() {
    assertNull(receiptStorePrefill(receipt()))
    assertNull(receiptStorePrefill(receipt(name = " ", address = "", ico = " ")))
  }
}
