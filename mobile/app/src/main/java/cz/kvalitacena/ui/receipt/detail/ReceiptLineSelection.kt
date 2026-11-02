package cz.kvalitacena.ui.receipt.detail

import cz.kvalitacena.network.ReceiptLine

// Čistá logika řádků účtenky bez závislosti na Androidu — výchozí zaškrtnutí k potvrzení
// a název řádku k zobrazení. Webový protějšek je `receipt-status.ts` (`defaultSelectedLineIds`).

/** Řádek jde potvrdit — návrh serveru i vlastní párování uživatele čekají jako SUGGESTED. */
internal fun ReceiptLine.isConfirmable(): Boolean = status == "SUGGESTED"

/**
 * Zboží je jen odhad podle podobnosti názvu (`matchSource == FUZZY`, docs/ai.md) — na rozdíl
 * od shody kódu nebo naučeného označení není jisté a potvrzení zboží připíše EAN z řádku.
 */
internal fun ReceiptLine.isFuzzyGuess(): Boolean = isConfirmable() && matchSource == "FUZZY"

/** Předem zaškrtnutý je jen jistý návrh; odhad musí uživatel zaškrtnout vědomě sám. */
internal fun ReceiptLine.isSelectedByDefault(): Boolean = isConfirmable() && !isFuzzyGuess()

internal fun defaultSelectedLineIds(lines: List<ReceiptLine>): Set<String> =
  lines.filter { it.isSelectedByDefault() }.mapTo(mutableSetOf()) { it.id }

/**
 * Výběr po načtení účtenky ze serveru. [reset] (první načtení, jiný obchod, po potvrzení)
 * vrátí výchozí zaškrtnutí; jinak ruční volbu drží — i zaškrtnutý odhad — a vyřadí jen řádky,
 * které už potvrdit nejde.
 */
internal fun selectionAfterLoad(previous: Set<String>, lines: List<ReceiptLine>, reset: Boolean): Set<String> {
  if (reset) return defaultSelectedLineIds(lines)
  val confirmable = lines.filter { it.isConfirmable() }.mapTo(mutableSetOf()) { it.id }
  return previous intersect confirmable
}

/**
 * Výběr po akci nad jedním řádkem (vybrat/zrušit párování/ignorovat): řádek se zaškrtne podle
 * stejného pravidla jako při načtení — vlastní párování ano, odhad ne.
 */
internal fun selectionAfterLineChange(previous: Set<String>, line: ReceiptLine): Set<String> =
  if (line.isSelectedByDefault()) previous + line.id else previous - line.id

/**
 * Hlavní text řádku: čitelný název od workeru („Rohlík 43g"), u starších účtenek syrový tisk.
 * Slouží i k předvyplnění nového zboží — párování se dál učí podle `labelRaw`.
 */
internal val ReceiptLine.shownLabel: String
  get() = labelDisplay?.trim()?.takeIf { it.isNotEmpty() } ?: labelRaw

/** Syrový tisk z pokladny jako sekundární údaj — jen když se od hlavního textu liší. */
internal val ReceiptLine.rawLabelIfDifferent: String?
  get() = labelRaw.takeIf { it.trim() != shownLabel.trim() }
