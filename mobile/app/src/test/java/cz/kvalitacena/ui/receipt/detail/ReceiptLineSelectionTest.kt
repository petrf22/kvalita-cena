package cz.kvalitacena.ui.receipt.detail

import cz.kvalitacena.network.Category
import cz.kvalitacena.network.ConfirmReceiptLinesResult
import cz.kvalitacena.network.ProductSummary
import cz.kvalitacena.network.Receipt
import cz.kvalitacena.network.ReceiptLine
import cz.kvalitacena.network.ReceiptLineCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptLineSelectionTest {

  private fun line(
    id: String,
    status: String = "SUGGESTED",
    matchSource: String? = null,
    labelRaw: String = "ROHLÍK43GR",
    labelDisplay: String? = null,
    confirmed: Boolean = false,
    skipReason: String? = null,
    nonProduct: Boolean = false,
    suspicious: Boolean = false,
    product: ProductSummary? = null,
    unitPrice: Double? = 10.0,
    lineTotal: Double? = 10.0,
    quantity: Double? = 1.0,
    quantityUnit: String = "PCS",
  ) = ReceiptLine(
    id = id,
    lineNo = id.toInt(),
    unitPrice = unitPrice,
    lineTotal = lineTotal,
    quantity = quantity,
    quantityUnit = quantityUnit,
    labelRaw = labelRaw,
    labelDisplay = labelDisplay,
    status = status,
    matchSource = matchSource,
    confirmed = confirmed,
    skipReason = skipReason,
    nonProduct = nonProduct,
    suspicious = suspicious,
    product = product,
  )

  private fun product(id: String, createdVia: String = "USER") = ProductSummary(
    id = id,
    name = "Rohlík",
    category = Category(id = "1", name = "Pečivo", slug = "pecivo", path = "pecivo"),
    createdVia = createdVia,
  )

  private fun receipt(
    lines: List<ReceiptLine> = emptyList(),
    sumStatus: String = "MATCH",
    totalAmount: Double? = 120.0,
    computedTotal: Double? = 120.0,
  ) = Receipt(
    id = "r1",
    processingStatus = "PROCESSED",
    reviewStatus = "PENDING",
    createdAt = "2026-11-05T10:00:00Z",
    totalAmount = totalAmount,
    computedTotal = computedTotal,
    sumStatus = sumStatus,
    lineCounts = ReceiptLineCounts(total = lines.size, unmatched = 0, suggested = 0, matched = 0, ignored = 0),
    lines = lines,
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
  fun predemZaskrtnuteJeVseKromeOdhaduHotovehoAIgnorovaneho() {
    // Nespárovaný řádek 6 se zveřejní jako zboží z účtenky; odhad 3, zapsaný 7 a ignorovaný 8 ne.
    assertEquals(setOf("1", "2", "4", "5", "6"), defaultSelectedLineIds(lines))
  }

  @Test
  fun radekBezCitelneCenyNejdeZaskrtnout() {
    val noPrice = line("2", status = "UNMATCHED", unitPrice = null, lineTotal = null, suspicious = true)
    assertFalse(noPrice.isPublishable())
    assertFalse(noPrice.isSelectedByDefault())
    assertTrue(noPrice.isPriceMissing())
    // Váhové zboží jen s celkovou částkou a bez množství: cenu za kg spočítat nejde.
    assertFalse(line("3", status = "UNMATCHED", unitPrice = null, lineTotal = 20.5, quantity = null, quantityUnit = "KG").isPublishable())
    // Kus s celkovou částkou: cena za balení z ní jde.
    val perPackage = line("4", status = "UNMATCHED", unitPrice = null, lineTotal = 20.5, quantity = null)
    assertTrue(perPackage.isPublishable())
    assertFalse(perPackage.isPriceMissing())
  }

  @Test
  fun nesparovanyRadekJdeZverejnitJakoZboziZUctenky() {
    val unmatched = line("6", status = "UNMATCHED")
    assertTrue(unmatched.isPublishable())
    assertTrue(unmatched.publishesAsReceiptItem())
    assertTrue(unmatched.isSelectedByDefault())
    // Návrh se zveřejní na navržené zboží, ne jako zboží z účtenky.
    assertFalse(line("1", matchSource = "CODE").publishesAsReceiptItem())
  }

  @Test
  fun nezboziBezZboziNejdeZverejnitANaNavrhuSeNezaskrtne() {
    // Záloha bez zboží: server ji vždy přeskočí (RECEIPT_LINE_NOT_PRODUCT) — zaškrtávátko by lhalo.
    val deposit = line("9", status = "UNMATCHED", nonProduct = true)
    assertFalse(deposit.isPublishable())
    assertFalse(deposit.isSelectedByDefault())
    // Záloha s návrhem zboží zveřejnit jde, ale jen vědomě.
    val depositSuggested = line("10", matchSource = "LABEL", nonProduct = true)
    assertTrue(depositSuggested.isPublishable())
    assertFalse(depositSuggested.isSelectedByDefault())
  }

  @Test
  fun podezrelyRadekJePredemZaskrtnutyJenZvyrazneny() {
    val suspicious = line("9", status = "UNMATCHED", suspicious = true)
    assertTrue(suspicious.isSelectedByDefault())
    assertTrue(suspicious.needsCheck(SumWarning.Unknown))
    // Se sedícím součtem se nezvýrazňuje — drobná nesrovnalost v řádku je tam jen šum.
    assertFalse(suspicious.needsCheck(null))
    // Hotový řádek už kontrolu nepotřebuje.
    assertFalse(line("11", status = "MATCHED", confirmed = true, suspicious = true).needsCheck(SumWarning.Unknown))
  }

  @Test
  fun potvrzenyAniIgnorovanyRadekNejdeZverejnit() {
    assertFalse(line("7", status = "MATCHED", matchSource = "CODE", confirmed = true).isPublishable())
    assertFalse(line("8", status = "IGNORED").isPublishable())
    // Duplicita týž den: MATCHED se skipReason není potvrzený, ale zveřejnit už nejde.
    assertFalse(line("12", status = "MATCHED", matchSource = "CODE", skipReason = "DUPLICATE_SAME_DAY").isPublishable())
  }

  @Test
  fun preskocenyRadekSePredemNezaskrtneKromeNesparovanehoZeStarsihoPotvrzeni() {
    // Chybějící cenu ani strop zboží z účtenek nový pokus nespraví — lišta by ho nabízela dokola.
    assertFalse(line("9", status = "UNMATCHED", skipReason = "RECEIPT_LINE_PRICE_MISSING").isSelectedByDefault())
    assertFalse(line("10", status = "UNMATCHED", skipReason = "RECEIPT_ITEM_LIMIT").isSelectedByDefault())
    // Ručně ho zaškrtnout jde.
    assertTrue(line("10", status = "UNMATCHED", skipReason = "RECEIPT_ITEM_LIMIT").isPublishable())
    // Dřív přeskočený jako „nemá zboží" teď publishUnmatched zveřejní.
    assertTrue(line("11", status = "UNMATCHED", skipReason = "RECEIPT_LINE_NOT_MATCHED").isSelectedByDefault())
  }

  @Test
  fun odesilaSeJenZaskrtnuteCoJdeZverejnit() {
    // 7 je zapsaný a 8 ignorovaný — i kdyby zůstaly ve výběru, neodešlou se.
    val sent = publishableSelection(lines, setOf("1", "3", "6", "7", "8")).map { it.id }
    assertEquals(listOf("1", "3", "6"), sent)
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
    assertEquals(setOf("1", "2", "4", "5", "6"), selectionAfterLoad(setOf("3"), lines, reset = true))
  }

  @Test
  fun bezResetuDrziRucniVolbuAVyradiNezverejnitelne() {
    // Uživatel odškrtl jistý návrh 1 a nespárovaný 6, zaškrtl odhad 3; 7 už je zapsaný, 8 ignorovaný.
    val previous = setOf("2", "3", "7", "8")
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
  fun zruseneParovaniZustaneZaskrtnuteJakoZboziZUctenky() {
    // „Zrušit párování" říká „tohle zboží to není" — cena se pak zveřejní na zboží z účtenky.
    assertEquals(setOf("1", "2"), selectionAfterLineChange(setOf("1", "2"), line("2", status = "UNMATCHED")))
  }

  @Test
  fun ignorovanyRadekSeOdskrtne() {
    assertEquals(setOf("1"), selectionAfterLineChange(setOf("1", "6"), line("6", status = "IGNORED")))
  }

  @Test
  fun sediciSoucetNeupozornuje() {
    assertNull(sumWarning(receipt(sumStatus = "MATCH")))
  }

  @Test
  fun nesediciSoucetNeseRozdil() {
    val warning = sumWarning(receipt(sumStatus = "MISMATCH", totalAmount = 120.0, computedTotal = 112.5))
    assertEquals(SumWarning.Mismatch(printedTotal = 120.0, computedTotal = 112.5, difference = 7.5), warning)
  }

  @Test
  fun nesediciSoucetBezCastkyJeBezRozdilu() {
    val warning = sumWarning(receipt(sumStatus = "MISMATCH", totalAmount = null, computedTotal = 112.5))
    assertEquals(SumWarning.Mismatch(printedTotal = null, computedTotal = 112.5, difference = null), warning)
  }

  @Test
  fun neznamyStavSouctuUpozorniJakoNeovereny() {
    assertEquals(SumWarning.Unknown, sumWarning(receipt(sumStatus = "UNKNOWN")))
    // Budoucí hodnota enumu, kterou appka nezná — upozornit navíc je bezpečnější než mlčet.
    assertEquals(SumWarning.Unknown, sumWarning(receipt(sumStatus = "PARTIAL")))
  }

  @Test
  fun souhrnPocitaZboziZUctenkyJenZOdeslanychRadku() {
    val after = listOf(
      line("1", status = "MATCHED", matchSource = "CODE", confirmed = true, product = product("p1")),
      line("2", status = "MATCHED", matchSource = "RECEIPT_ITEM", confirmed = true, product = product("p2", "RECEIPT")),
      // Naučené označení z dřívější účtenky míří na existující zboží z účtenky — cena je taky na něm.
      line("3", status = "MATCHED", matchSource = "LABEL", confirmed = true, product = product("p3", "RECEIPT")),
      // Zboží z účtenky zveřejněné dřív — v souhrnu tohohle zveřejnění být nemá.
      line("4", status = "MATCHED", matchSource = "RECEIPT_ITEM", confirmed = true, product = product("p4", "RECEIPT")),
      line("5", status = "UNMATCHED", skipReason = "RECEIPT_ITEM_LIMIT"),
    )
    val skipped = listOf(after.last())
    val result = ConfirmReceiptLinesResult(receipt = receipt(after), confirmed = 3, skipped = skipped)
    val summary = publishSummary(listOf("1", "2", "3", "5"), result)
    assertEquals(PublishSummary(published = 3, asReceiptItems = 2, skipped = skipped), summary)
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

