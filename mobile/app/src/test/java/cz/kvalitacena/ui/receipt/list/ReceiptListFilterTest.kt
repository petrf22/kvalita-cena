package cz.kvalitacena.ui.receipt.list

import cz.kvalitacena.network.ReceiptFilterCounts
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptListFilterTest {

  private fun counts(all: Int = 0, toReview: Int = 0, processing: Int = 0, done: Int = 0, failed: Int = 0) =
    ReceiptFilterCounts(all = all, toReview = toReview, processing = processing, done = done, failed = failed)

  @Test
  fun kVyrizeniKdyzNecoCekaNaParovani() {
    assertEquals(ReceiptListFilter.TO_REVIEW, initialReceiptFilter(counts(all = 30, toReview = 1, done = 29)))
  }

  @Test
  fun vseKdyzNicNecekaNaVyrizeni() {
    assertEquals(ReceiptListFilter.ALL, initialReceiptFilter(counts(all = 5, processing = 2, done = 2, failed = 1)))
  }

  @Test
  fun vseKdyzUcetNemaZadnouUctenku() {
    assertEquals(ReceiptListFilter.ALL, initialReceiptFilter(counts()))
  }

  @Test
  fun prvniDotazSePtaNaKVyrizeni() {
    // Běžný případ (něco čeká) tak obslouží jediný dotaz; druhý jen když initialReceiptFilter vrátí ALL.
    assertEquals(ReceiptListFilter.TO_REVIEW, FIRST_RECEIPT_FILTER)
  }

  @Test
  fun pocetPodleSkupiny() {
    val c = counts(all = 15, toReview = 1, processing = 2, done = 4, failed = 8)
    assertEquals(15, c.countFor(ReceiptListFilter.ALL))
    assertEquals(1, c.countFor(ReceiptListFilter.TO_REVIEW))
    assertEquals(2, c.countFor(ReceiptListFilter.PROCESSING))
    assertEquals(4, c.countFor(ReceiptListFilter.DONE))
    assertEquals(8, c.countFor(ReceiptListFilter.FAILED))
  }
}
