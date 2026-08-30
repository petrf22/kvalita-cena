package cz.kvalitacena.ui.price

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun row(
  id: Long = 1L,
  priceKind: String = "REGULAR",
  priceAmount: String = "",
  multibuyQty: String = "",
  multibuyTotal: String = "",
  promoValidFrom: String = "",
  promoValidTo: String = "",
) = PriceRow(id, priceKind, priceAmount, multibuyQty, multibuyTotal, promoValidFrom, promoValidTo)

class PriceRowValidationTest {

  @Test
  fun dotOnlyIsNotAValidAmount() {
    // Dřív tudy propadávala tichá díra — PriceEntryViewModel.submit() dřív skončila na
    // toDoubleOrNull() ?: return, ale tlačítko svítilo, protože "." projde isNotBlank().
    assertNull(parseAmount("."))
    assertFalse(isPriceRowValid(row(priceAmount = ".")))
  }

  @Test
  fun commaAndDotAreBothAccepted() {
    assertEquals(29.9, parseAmount("29,9"))
    assertEquals(29.9, parseAmount("29.9"))
  }

  @Test
  fun multibuyNeedsQuantityAndTotal() {
    assertTrue(isPriceRowValid(row(priceKind = "MULTIBUY", multibuyQty = "3", multibuyTotal = "50")))
    assertFalse(isPriceRowValid(row(priceKind = "MULTIBUY", multibuyQty = "1", multibuyTotal = "50")))
    assertFalse(isPriceRowValid(row(priceKind = "MULTIBUY", multibuyQty = "", multibuyTotal = "50")))
    assertFalse(isPriceRowValid(row(priceKind = "MULTIBUY", multibuyQty = "3", multibuyTotal = "")))
  }

  @Test
  fun duplicatePriceKindIsDetected() {
    assertNull(duplicatePriceKind(listOf(row(id = 1, priceKind = "REGULAR"), row(id = 2, priceKind = "CLUB_CARD"))))
    assertEquals(
      "REGULAR",
      duplicatePriceKind(
        listOf(
          row(id = 1, priceKind = "REGULAR"),
          row(id = 2, priceKind = "CLUB_CARD"),
          row(id = 3, priceKind = "REGULAR"),
        ),
      ),
    )
  }

  @Test
  fun arePriceRowsValidRejectsEmptyListAndDuplicates() {
    assertFalse(arePriceRowsValid(emptyList()))
    assertFalse(
      arePriceRowsValid(
        listOf(
          row(id = 1, priceKind = "REGULAR", priceAmount = "10"),
          row(id = 2, priceKind = "REGULAR", priceAmount = "20"),
        ),
      ),
    )
    assertTrue(
      arePriceRowsValid(
        listOf(
          row(id = 1, priceKind = "REGULAR", priceAmount = "29.9"),
          row(id = 2, priceKind = "CLUB_CARD", priceAmount = "24.9"),
        ),
      ),
    )
  }

  @Test
  fun availableKindsExcludeKindsUsedInOtherRows() {
    val rows = listOf(row(id = 1, priceKind = "REGULAR"), row(id = 2, priceKind = "CLUB_CARD"))
    val available = availablePriceKinds(rows, "CLUB_CARD")
    assertTrue(available.contains("CLUB_CARD"))
    assertFalse(available.contains("REGULAR"))
  }

  @Test
  fun firstAvailablePriceKindSkipsKindsAlreadyUsed() {
    val used = firstAvailablePriceKind(listOf(row(id = 1, priceKind = "REGULAR")))
    assertFalse(used == "REGULAR")
  }

  @Test
  fun mappingDropsPriceAmountForMultibuy() {
    val inputs = toObservationPriceInputs(
      listOf(row(priceKind = "MULTIBUY", multibuyQty = "3", multibuyTotal = "50")),
    )
    assertEquals(1, inputs.size)
    assertEquals("MULTIBUY", inputs[0].priceKind)
    assertNull(inputs[0].priceAmount)
    assertEquals(3, inputs[0].multibuyQty)
    assertEquals(50.0, inputs[0].multibuyTotal)
  }

  @Test
  fun mappingDropsMultibuyFieldsForRegular() {
    val inputs = toObservationPriceInputs(listOf(row(priceKind = "REGULAR", priceAmount = "29.9")))
    assertEquals(1, inputs.size)
    assertEquals(29.9, inputs[0].priceAmount)
    assertNull(inputs[0].multibuyQty)
    assertNull(inputs[0].multibuyTotal)
  }

  @Test
  fun promoAllowsNoValidityDates() {
    assertTrue(isPriceRowValid(row(priceKind = "PROMO", priceAmount = "19.9")))
  }

  @Test
  fun promoAllowsAValidityRangeInThePastOrNearFuture() {
    val from = LocalDate.now().minusDays(2).toString()
    val to = LocalDate.now().plusDays(5).toString()
    assertTrue(isPriceRowValid(row(priceKind = "PROMO", priceAmount = "19.9", promoValidFrom = from, promoValidTo = to)))
  }

  @Test
  fun validityDatesAreRejectedOnAnyKindOtherThanPromo() {
    val to = LocalDate.now().plusDays(5).toString()
    assertFalse(isPriceRowValid(row(priceKind = "REGULAR", priceAmount = "29.9", promoValidTo = to)))
  }

  @Test
  fun promoValidFromAfterValidToIsRejected() {
    val from = LocalDate.now().toString()
    val to = LocalDate.now().minusDays(1).toString()
    assertFalse(isPriceRowValid(row(priceKind = "PROMO", priceAmount = "19.9", promoValidFrom = from, promoValidTo = to)))
  }

  @Test
  fun promoValidFromInTheFutureIsRejected() {
    val from = LocalDate.now().plusDays(1).toString()
    assertFalse(isPriceRowValid(row(priceKind = "PROMO", priceAmount = "19.9", promoValidFrom = from)))
  }

  @Test
  fun mappingSendsValidityDatesOnlyForPromo() {
    val promoInputs = toObservationPriceInputs(
      listOf(row(priceKind = "PROMO", priceAmount = "19.9", promoValidFrom = "2026-08-01", promoValidTo = "2026-08-31")),
    )
    assertEquals("2026-08-01", promoInputs[0].promoValidFrom)
    assertEquals("2026-08-31", promoInputs[0].promoValidTo)

    val regularInputs = toObservationPriceInputs(listOf(row(priceKind = "REGULAR", priceAmount = "29.9")))
    assertNull(regularInputs[0].promoValidFrom)
    assertNull(regularInputs[0].promoValidTo)
  }

  @Test
  fun observedAtBlankMeansServerDefaultsToNow() {
    assertNull(toObservedAtIso(""))
  }

  @Test
  fun observedAtTodayAlsoMeansServerDefaultsToNow() {
    // Dopolední zápis "dneška" by jinak s pevným polednem mohl vyjít na čas v budoucnu.
    assertNull(toObservedAtIso(LocalDate.now().toString()))
  }

  @Test
  fun observedAtPastDateKeepsTheSameCalendarDay() {
    val yesterday = LocalDate.now().minusDays(1)
    val iso = toObservedAtIso(yesterday.toString())
    assertTrue(iso != null && iso.startsWith("${yesterday}T12:00"))
  }
}
