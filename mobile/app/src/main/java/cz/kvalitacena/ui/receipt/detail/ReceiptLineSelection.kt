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

/**
 * Návrh ze zboží, které zná jen Open Food Facts (`matchSource == OFF`) — zboží zatím
 * neexistuje, založí se potvrzením řádku. Jistý jako shoda EAN, proto předem zaškrtnutý.
 */
internal fun ReceiptLine.isOffSuggestion(): Boolean = isConfirmable() && matchSource == "OFF"

/** Skupiny seznamu řádků — od toho, co potřebuje člověka, po hotové. */
internal enum class LineGroup {
  /** Appka zboží nezná — uživatel ho musí najít, založit nebo řádek ignorovat. */
  TO_RESOLVE,

  /** Odhad podle názvu — stačí potvrdit „ano, je to ono", nebo vybrat jiné. */
  TO_CHECK,

  /** Jistý návrh (kód, naučené označení, OFF) nebo vlastní párování — čeká jen na potvrzení. */
  READY,

  /** Cena zapsaná. */
  DONE,

  IGNORED,
}

internal fun ReceiptLine.group(): LineGroup = when {
  status == "IGNORED" -> LineGroup.IGNORED
  status == "MATCHED" || confirmed -> LineGroup.DONE
  isFuzzyGuess() -> LineGroup.TO_CHECK
  isConfirmable() -> LineGroup.READY
  else -> LineGroup.TO_RESOLVE
}

/** Řádky ve skupinách v pořadí [LineGroup] — prázdné skupiny vynechané, pořadí z účtenky uvnitř. */
internal fun groupedLines(lines: List<ReceiptLine>): List<Pair<LineGroup, List<ReceiptLine>>> =
  LineGroup.entries.mapNotNull { group ->
    lines.filter { it.group() == group }.takeIf { it.isNotEmpty() }?.let { group to it }
  }

/**
 * Fronta průvodce „Doplnit nerozpoznané": nejdřív řádky, které appka nezná, pak odhady ke
 * kontrole — jen to, na co appka sama nestačí. Jisté návrhy průvodce neukazuje, ty stačí
 * hromadně potvrdit.
 */
internal fun guideQueue(lines: List<ReceiptLine>): List<String> =
  lines.filter { it.group() == LineGroup.TO_RESOLVE }.map { it.id } +
    lines.filter { it.group() == LineGroup.TO_CHECK }.map { it.id }

/**
 * Další řádek průvodce po [currentId]: první z [queue] za ním, který pořád potřebuje
 * člověka (mezitím ho mohl vyřešit jiný krok — naučené označení spárovalo i další řádky).
 * `null` = průvodce skončil.
 */
internal fun nextInGuide(queue: List<String>, currentId: String?, lines: List<ReceiptLine>): String? {
  val needsHuman = lines.filter { it.group() == LineGroup.TO_RESOLVE || it.group() == LineGroup.TO_CHECK }
    .mapTo(mutableSetOf()) { it.id }
  val start = if (currentId == null) 0 else queue.indexOf(currentId) + 1
  return queue.drop(start.coerceAtLeast(0)).firstOrNull { it in needsHuman }
}
