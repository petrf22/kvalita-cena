package cz.kvalitacena.ui.receipt.detail

import cz.kvalitacena.network.ConfirmReceiptLinesResult
import cz.kvalitacena.network.Receipt
import cz.kvalitacena.network.ReceiptLine
import cz.kvalitacena.ui.common.isReceiptItem

// Čistá logika řádků účtenky bez závislosti na Androidu — výchozí zaškrtnutí ke zveřejnění,
// kontrolní součet a název řádku k zobrazení. Webový protějšek je `receipt-status.ts`
// (`defaultSelectedLineIds`).

/** Řádek má návrh zboží — návrh serveru i vlastní párování uživatele čekají jako SUGGESTED. */
internal fun ReceiptLine.isConfirmable(): Boolean = status == "SUGGESTED"

/**
 * Řádek jde zveřejnit (`confirmReceiptLines(publishUnmatched: true)`): návrh, nebo nespárovaný
 * řádek — ten server zapíše na „zboží z účtenky" (docs/reputace.md). Nespárované nezboží
 * (záloha, vratný obal) ne: server ho vždy přeskočí s `RECEIPT_LINE_NOT_PRODUCT`, zaškrtávátko
 * by jen slibovalo něco, co se nestane. Nezboží s návrhem zboží zveřejnit jde — návrh je
 * párování jako každé jiné, jen se předem nezaškrtne ([isSelectedByDefault]).
 */
internal fun ReceiptLine.isPublishable(): Boolean =
  !confirmed && isPriceKnown() && (isConfirmable() || (status == "UNMATCHED" && !nonProduct))

/**
 * Z řádku jde zapsat cena — stejné pravidlo jako server (`ReceiptReviewService.priceRows`):
 * kladná jednotková cena, nebo celková částka u kusového zboží či se známým množstvím. Bez ní
 * server řádek vždy přeskočí (`RECEIPT_LINE_PRICE_MISSING`), zaškrtávátko by jen slibovalo
 * něco, co se nestane. Web: `isLinePriceKnown` v `receipt-status.ts`.
 */
internal fun ReceiptLine.isPriceKnown(): Boolean {
  if ((unitPrice ?: 0.0) > 0.0) return true
  val total = lineTotal ?: 0.0
  return total > 0.0 && (quantityUnit == "PCS" || (quantity ?: 0.0) > 0.0)
}

/** Nevyřízený řádek, ze kterého cenu zapsat nejde — klient řekne proč, místo zaškrtávátka. */
internal fun ReceiptLine.isPriceMissing(): Boolean = !confirmed && status != "IGNORED" && !isPriceKnown()

/** Zveřejní se bez spárování — cena se zapíše na „zboží z účtenky". */
internal fun ReceiptLine.publishesAsReceiptItem(): Boolean = isPublishable() && status == "UNMATCHED"

/**
 * Zboží je jen odhad podle podobnosti názvu (`matchSource == FUZZY`, docs/ai.md) — na rozdíl
 * od shody kódu nebo naučeného označení není jisté a potvrzení zboží připíše EAN z řádku.
 */
internal fun ReceiptLine.isFuzzyGuess(): Boolean = isConfirmable() && matchSource == "FUZZY"

/**
 * Proč potvrzení řádek přeskočilo, a nové zveřejnění to přesto může vyřešit: dřív se
 * nespárovaný řádek bez `publishUnmatched` přeskočil jako „nemá zboží" — teď se zveřejní na
 * zboží z účtenky.
 */
private const val SKIP_SOLVED_BY_PUBLISHING = "RECEIPT_LINE_NOT_MATCHED"

/**
 * Předem zaškrtnuté ke zveřejnění je vše, co zveřejnit jde (docs/ai.md, „Kontrolní součet řídí
 * předvýběr, nic nezakazuje") — i nespárované řádky a řádky podezřelé z chyby čtení (ty jen
 * zvýrazní upozornění, odškrtnout je musí uživatel). Výjimky:
 *
 * - **odhad podle názvu (FUZZY)** — zveřejnění by ho potvrdilo: naučí se jako označení řetězce
 *   a zboží dostane EAN z řádku, takže omyl by se rozšířil i na účtenky ostatních (docs/ai.md).
 *   Zveřejnit ho jako zboží z účtenky místo odhadu bez „Zrušit párování" nejde — server zboží
 *   z odhadu použije. Odhad proto dál zaškrtává jen uživatel, vědomě;
 * - **nezboží** (záloha, vratný obal);
 * - **řádek, který už jednou neprošel** (`skipReason`) — chybějící cena ani strop zboží
 *   z účtenek se novým pokusem nespraví a hned po souhrnu „přeskočeno, protože…" by ho lišta
 *   nabízela odeslat znovu. Kromě [SKIP_SOLVED_BY_PUBLISHING].
 */
internal fun ReceiptLine.isSelectedByDefault(): Boolean =
  isPublishable() && !isFuzzyGuess() && !nonProduct &&
    (skipReason == null || skipReason == SKIP_SOLVED_BY_PUBLISHING)

internal fun defaultSelectedLineIds(lines: List<ReceiptLine>): Set<String> =
  lines.filter { it.isSelectedByDefault() }.mapTo(mutableSetOf()) { it.id }

/** Zaškrtnuté řádky, které jde opravdu odeslat — v pořadí účtenky. */
internal fun publishableSelection(lines: List<ReceiptLine>, selected: Set<String>): List<ReceiptLine> =
  lines.filter { it.isPublishable() && it.id in selected }

/**
 * Výběr po načtení účtenky ze serveru. [reset] (první načtení, jiný obchod, po zveřejnění)
 * vrátí výchozí zaškrtnutí; jinak ruční volbu drží — i zaškrtnutý odhad — a vyřadí jen řádky,
 * které už zveřejnit nejde.
 */
internal fun selectionAfterLoad(previous: Set<String>, lines: List<ReceiptLine>, reset: Boolean): Set<String> {
  if (reset) return defaultSelectedLineIds(lines)
  val publishable = lines.filter { it.isPublishable() }.mapTo(mutableSetOf()) { it.id }
  return previous intersect publishable
}

/**
 * Výběr po akci nad jedním řádkem (vybrat/zrušit párování/ignorovat/vrátit): řádek se zaškrtne
 * podle stejného pravidla jako při načtení — vlastní párování ano, odhad ne, zrušené párování
 * a vrácený ignorovaný řádek ano (zveřejní se jako zboží z účtenky), ignorovaný ne.
 */
internal fun selectionAfterLineChange(previous: Set<String>, line: ReceiptLine): Set<String> =
  if (line.isSelectedByDefault()) previous + line.id else previous - line.id

/** Upozornění nad seznamem řádků podle kontrolního součtu workeru (`Receipt.sumStatus`). */
internal sealed interface SumWarning {
  /** Součet položek nesedí s vytištěnou částkou; [difference] = vytištěno − součet, když jsou známé obě. */
  data class Mismatch(val printedTotal: Double?, val computedTotal: Double?, val difference: Double?) : SumWarning

  /** Celkem se nenašlo, nebo účtenku zpracovala starší verze workeru. */
  data object Unknown : SumWarning
}

/**
 * `MATCH` → nic; `MISMATCH` → rozdíl; cokoli jiného (i budoucí hodnota enumu) → „nešlo ověřit"
 * — upozornit navíc je bezpečnější než mlčet nad účtenkou, které nikdo nepřepočítal.
 */
internal fun sumWarning(receipt: Receipt): SumWarning? = when (receipt.sumStatus) {
  "MATCH" -> null
  "MISMATCH" -> {
    val printed = receipt.totalAmount
    val computed = receipt.computedTotal
    SumWarning.Mismatch(printed, computed, if (printed != null && computed != null) printed - computed else null)
  }
  else -> SumWarning.Unknown
}

/**
 * Řádek se zvýrazní ke kontrole: podezřelý z chyby čtení (`ReceiptLine.suspicious`), ještě
 * nezveřejněný a jen tehdy, když kontrolní součet nesedí nebo nešel ověřit — se sedícím
 * součtem je drobná nesrovnalost v řádku (zaokrouhlení váhového zboží) jen šum.
 */
internal fun ReceiptLine.needsCheck(warning: SumWarning?): Boolean =
  warning != null && suspicious && isPublishable()

/** Souhrn po „Zveřejnit vybrané" — kolik cen se zapsalo, z toho na zboží z účtenky, a co ne. */
internal data class PublishSummary(
  val published: Int,
  val asReceiptItems: Int,
  val skipped: List<ReceiptLine>,
)

/**
 * [sentIds] jsou řádky, které klient poslal. Na zboží z účtenky se cena zapsala, když řádek
 * po zveřejnění míří na zboží s `createdVia = RECEIPT` — patří sem i řádek, který server
 * spároval podle označení naučeného z dřívější účtenky (`matchSource = LABEL`) na existující
 * zboží z účtenky. `RECEIPT_ITEM` platí i bez načteného zboží (skryté, smazané).
 */
internal fun publishSummary(sentIds: Collection<String>, result: ConfirmReceiptLinesResult): PublishSummary {
  val sent = sentIds.toSet()
  val asItems = result.receipt.lines.count { line ->
    line.id in sent && line.confirmed &&
      (line.matchSource == "RECEIPT_ITEM" || line.product?.isReceiptItem == true)
  }
  return PublishSummary(published = result.confirmed, asReceiptItems = asItems, skipped = result.skipped)
}

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
  /**
   * Appka zboží nezná — uživatel ho může najít, založit nebo řádek ignorovat; jinak se
   * zveřejní jako zboží z účtenky ([publishesAsReceiptItem]).
   */
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
