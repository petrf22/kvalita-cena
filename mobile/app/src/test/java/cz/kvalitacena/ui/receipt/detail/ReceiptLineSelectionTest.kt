package cz.kvalitacena.ui.receipt.detail

import cz.kvalitacena.network.ReceiptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptLineSelectionTest {

  private fun line(
    id: String,
    status: String = "SUGGESTED",
    matchSource: String? = null,
    labelRaw: String = "ROHLÍK43GR",
    labelDisplay: String? = null,
  ) = ReceiptLine(
    id = id,
    lineNo = id.toInt(),
    labelRaw = labelRaw,
    labelDisplay = labelDisplay,
    status = status,
    matchSource = matchSource,
  )

  private val lines = listOf(
    line("1", matchSource = "CODE"),
    line("2", matchSource = "LABEL"),
    line("3", matchSource = "FUZZY"),
    line("4", matchSource = "USER"),
    line("5", matchSource = "NEW_PRODUCT"),
    line("6", status = "UNMATCHED"),
    line("7", status = "MATCHED", matchSource = "CODE"),
    line("8", status = "IGNORED"),
  )

  @Test
  fun odhadPodleNazvuNeniPredemZaskrtnuty() {
    assertEquals(setOf("1", "2", "4", "5"), defaultSelectedLineIds(lines))
  }

  @Test
  fun odhadJdePotvrditJakoJinyNavrh() {
    val fuzzy = lines.first { it.id == "3" }
    assertEquals(true, fuzzy.isConfirmable())
    assertEquals(true, fuzzy.isFuzzyGuess())
    assertEquals(false, fuzzy.isSelectedByDefault())
  }

  @Test
  fun fuzzyMimoNavrhNeniOdhad() {
    // Po potvrzení zůstane matchSource FUZZY, ale řádek už je MATCHED — štítek „odhad" nepatří.
    assertEquals(false, line("9", status = "MATCHED", matchSource = "FUZZY").isFuzzyGuess())
  }

  @Test
  fun resetVratiVychoziVyber() {
    assertEquals(setOf("1", "2", "4", "5"), selectionAfterLoad(setOf("3", "6"), lines, reset = true))
  }

  @Test
  fun bezResetuDrziRucneZaskrtnutyOdhadAVyradiNepotvrditelne() {
    // Uživatel odškrtl jistý návrh 1 a zaškrtl odhad 3; 7 už je zapsaný, 6 nemá zboží.
    val previous = setOf("2", "3", "6", "7")
    assertEquals(setOf("2", "3"), selectionAfterLoad(previous, lines, reset = false))
  }

  @Test
  fun vlastniParovaniSeZaskrtne() {
    val matched = line("6", matchSource = "USER")
    assertEquals(setOf("1", "6"), selectionAfterLineChange(setOf("1"), matched))
  }

  @Test
  fun odhadPoAkciSeNezaskrtne() {
    assertEquals(setOf("1"), selectionAfterLineChange(setOf("1", "3"), line("3", matchSource = "FUZZY")))
  }

  @Test
  fun zruseneParovaniSeOdskrtne() {
    assertEquals(setOf("1"), selectionAfterLineChange(setOf("1", "2"), line("2", status = "UNMATCHED")))
  }

  @Test
  fun citelnyNazevSPuvodnimTiskem() {
    val l = line("1", labelRaw = "ROHLÍK43GR", labelDisplay = "Rohlík 43g")
    assertEquals("Rohlík 43g", l.shownLabel)
    assertEquals("ROHLÍK43GR", l.rawLabelIfDifferent)
  }

  @Test
  fun starsiUctenkaBezCitelnehoNazvu() {
    val l = line("1", labelRaw = "ROHLÍK43GR", labelDisplay = null)
    assertEquals("ROHLÍK43GR", l.shownLabel)
    assertNull(l.rawLabelIfDifferent)
  }

  @Test
  fun prazdnyCitelnyNazevSeNebere() {
    val l = line("1", labelRaw = "ROHLÍK43GR", labelDisplay = "  ")
    assertEquals("ROHLÍK43GR", l.shownLabel)
    assertNull(l.rawLabelIfDifferent)
  }

  @Test
  fun stejnyNazevSeNeopakuje() {
    val l = line("1", labelRaw = "Mléko 1l ", labelDisplay = "Mléko 1l")
    assertEquals("Mléko 1l", l.shownLabel)
    assertNull(l.rawLabelIfDifferent)
  }

  @Test
  fun navrhZOpenFoodFactsJePredemZaskrtnuty() {
    val off = line("9", matchSource = "OFF")
    assertEquals(setOf("9"), defaultSelectedLineIds(listOf(off)))
    assertEquals(LineGroup.READY, off.group())
  }

  @Test
  fun skupinyJdouOdToHoCoPotrebujeCloveka() {
    val groups = groupedLines(lines).map { (group, items) -> group to items.map { it.id } }
    assertEquals(
      listOf(
        LineGroup.TO_RESOLVE to listOf("6"),
        LineGroup.TO_CHECK to listOf("3"),
        LineGroup.READY to listOf("1", "2", "4", "5"),
        LineGroup.DONE to listOf("7"),
        LineGroup.IGNORED to listOf("8"),
      ),
      groups,
    )
  }

  @Test
  fun pruvodceProjdeNespárovanéAPakOdhady() {
    val withMore = lines + line("10", status = "UNMATCHED")
    assertEquals(listOf("6", "10", "3"), guideQueue(withMore))
  }

  @Test
  fun pruvodcePreskociRadekKteryMezitimNekdoVyresil() {
    val queue = listOf("6", "10", "3")
    val resolved = lines + line("10", matchSource = "LABEL")
    assertEquals("3", nextInGuide(queue, "6", resolved))
    assertEquals("6", nextInGuide(queue, null, resolved))
    assertNull(nextInGuide(queue, "3", resolved))
  }
}

