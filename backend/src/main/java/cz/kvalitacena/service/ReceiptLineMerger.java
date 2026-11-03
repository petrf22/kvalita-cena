package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.ReceiptLine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Sloučí řádky účtenky z nového rozpoznání se stávajícími (docs/ai.md, „Nové rozpoznání").
 * Řádky, na které uživatel sáhl ({@link ReceiptLine#isUserTouched()}), zůstávají beze změny
 * stavu, zboží i ceny; ostatní nahradí nová verze. Nový řádek, který odpovídá ponechanému, se
 * zahodí — jinak by se potvrzená položka objevila podruhé k potvrzení.
 *
 * <p>Párování ponechaný ↔ nový je 1:1 ve dvou průchodech:
 * <ol>
 *   <li>stejné syrové označení (bez velikosti písmen a mezer) a stejná částka řádku,</li>
 *   <li>stejná částka, jednotková cena i množství — oprava OCR typicky mění právě označení;
 *       páruje se jen vzájemně jednoznačná dvojice.</li>
 * </ol>
 * Nespárovaný ponechaný řádek zůstane na konci. Horší případ je tedy řádek navíc, který
 * uživatel ignoruje — dvojí potvrzení téže ceny týž den stejně odmítne unikátní index observace.
 *
 * <p>Čistá logika bez DB; čísla řádků přiděluje volající ({@code uq_receipt_line_no} není
 * deferrable).
 */
final class ReceiptLineMerger {

  private ReceiptLineMerger() {
  }

  /**
   * @param removed  stávající nedotčené řádky ke smazání
   * @param added    indexy nových řádků (do {@code incoming}), které se vloží
   * @param ordered  výsledné pořadí všech řádků (ponechané i vložené)
   */
  record Result(List<ReceiptLine> removed, List<Integer> added, List<ReceiptLine> ordered) {
  }

  static Result merge(List<ReceiptLine> existing, List<ReceiptLine> incoming) {
    List<ReceiptLine> kept = existing.stream()
        .filter(ReceiptLine::isUserTouched)
        .sorted(Comparator.comparing(ReceiptLine::getLineNo))
        .toList();
    List<ReceiptLine> removed = existing.stream().filter(l -> !l.isUserTouched()).toList();

    // pairOf[i] = ponechaný řádek, který zastoupí nový řádek i
    ReceiptLine[] pairOf = new ReceiptLine[incoming.size()];
    List<ReceiptLine> unpaired = new ArrayList<>();
    for (ReceiptLine line : kept) {
      int match = -1;
      for (int i = 0; i < incoming.size() && match < 0; i++) {
        if (pairOf[i] == null && sameLabelAndTotal(line, incoming.get(i))) {
          match = i;
        }
      }
      if (match >= 0) {
        pairOf[match] = line;
      } else {
        unpaired.add(line);
      }
    }

    List<ReceiptLine> stillUnpaired = new ArrayList<>();
    for (ReceiptLine line : unpaired) {
      int match = uniqueAmountMatch(line, incoming, pairOf, unpaired);
      if (match >= 0) {
        pairOf[match] = line;
      } else {
        stillUnpaired.add(line);
      }
    }

    List<Integer> added = new ArrayList<>();
    List<ReceiptLine> ordered = new ArrayList<>();
    for (int i = 0; i < incoming.size(); i++) {
      if (pairOf[i] != null) {
        takeCosmetics(pairOf[i], incoming.get(i));
        ordered.add(pairOf[i]);
      } else {
        added.add(i);
        ordered.add(incoming.get(i));
      }
    }
    ordered.addAll(stillUnpaired);
    return new Result(removed, added, ordered);
  }

  /**
   * Obsah řádků se nezměnil — nové rozpoznání dalo totéž (včetně výsledku párování), takže
   * vlastníka není proč upozorňovat.
   */
  static boolean sameContent(List<ReceiptLine> before, List<ReceiptLine> after) {
    if (before.size() != after.size()) {
      return false;
    }
    List<ReceiptLine> a = before.stream().sorted(Comparator.comparing(ReceiptLine::getLineNo)).toList();
    for (int i = 0; i < a.size(); i++) {
      if (!signature(a.get(i)).equals(signature(after.get(i)))) {
        return false;
      }
    }
    return true;
  }

  private static List<Object> signature(ReceiptLine l) {
    return Arrays.asList(l.getLabelRaw(), l.getLabelDisplay(), l.getInternalCode(),
        num(l.getQuantity()), l.getQuantityUnit(), num(l.getUnitPrice()), num(l.getLineTotal()),
        num(l.getDiscountAmount()), l.getPriceKindGuess(), l.getStatus(), l.getProductId(), l.getMatchSource());
  }

  private static BigDecimal num(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros();
  }

  /** Jen to, co je pro oči — nikdy syrový tisk (podle něj se učí mapování), zboží ani cena. */
  private static void takeCosmetics(ReceiptLine kept, ReceiptLine fresh) {
    if (fresh.getLabelDisplay() != null) {
      kept.setLabelDisplay(fresh.getLabelDisplay());
      kept.setLabelDisplaySource(fresh.getLabelDisplaySource());
    }
  }

  private static boolean sameLabelAndTotal(ReceiptLine a, ReceiptLine b) {
    return normLabel(a.getLabelRaw()).equals(normLabel(b.getLabelRaw())) && sameAmount(a.getLineTotal(), b.getLineTotal());
  }

  /**
   * Index nového řádku se stejnými částkami — jen když je pro ponechaný řádek jediný a zároveň
   * mu neodpovídá žádný jiný nespárovaný ponechaný řádek.
   */
  private static int uniqueAmountMatch(ReceiptLine line, List<ReceiptLine> incoming, ReceiptLine[] pairOf,
      List<ReceiptLine> unpaired) {
    if (line.getLineTotal() == null) {
      return -1;
    }
    int match = -1;
    for (int i = 0; i < incoming.size(); i++) {
      if (pairOf[i] == null && sameAmounts(line, incoming.get(i))) {
        if (match >= 0) {
          return -1;
        }
        match = i;
      }
    }
    if (match < 0) {
      return -1;
    }
    ReceiptLine candidate = incoming.get(match);
    long rivals = unpaired.stream().filter(other -> other != line && sameAmounts(other, candidate)).count();
    return rivals == 0 ? match : -1;
  }

  private static boolean sameAmounts(ReceiptLine a, ReceiptLine b) {
    return a.getLineTotal() != null && sameAmount(a.getLineTotal(), b.getLineTotal())
        && sameAmount(a.getUnitPrice(), b.getUnitPrice())
        && sameAmount(a.getQuantity(), b.getQuantity());
  }

  private static boolean sameAmount(BigDecimal a, BigDecimal b) {
    return a == null ? b == null : b != null && a.compareTo(b) == 0;
  }

  private static String normLabel(String label) {
    return Objects.requireNonNullElse(label, "").trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
  }
}
