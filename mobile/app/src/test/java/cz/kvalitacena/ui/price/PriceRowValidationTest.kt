package cz.kvalitacena.ui.price

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
) = PriceRow(id, priceKind, priceAmount, multibuyQty, multibuyTotal)

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
}
