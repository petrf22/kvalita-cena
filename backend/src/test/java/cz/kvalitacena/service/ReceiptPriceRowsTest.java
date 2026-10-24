package cz.kvalitacena.service;

import cz.kvalitacena.controller.ObservationPriceInput;
import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.QuantityBasis;
import cz.kvalitacena.db.entity.QuantityUnit;
import cz.kvalitacena.db.entity.ReceiptLine;
import cz.kvalitacena.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Řádek účtenky → ceny k zápisu (docs/rozvoj.md, „Co se nesmí zapomenout"). */
class ReceiptPriceRowsTest {

  @Test
  void weighedGoodsUsePricePerKgNotPaidAmount() {
    var rows = ReceiptReviewService.priceRows(line("0.432", QuantityUnit.KG, "29.90", "12.92", null, PriceKind.REGULAR));
    assertThat(rows.basis()).isEqualTo(QuantityBasis.PER_KG);
    assertThat(rows.prices()).singleElement()
        .satisfies(p -> assertThat(p.priceAmount()).isEqualByComparingTo("29.90"));
  }

  @Test
  void missingUnitPriceIsDerivedFromTotal() {
    var rows = ReceiptReviewService.priceRows(line("3", QuantityUnit.PCS, null, "8.70", null, PriceKind.REGULAR));
    assertThat(rows.basis()).isEqualTo(QuantityBasis.PACKAGE);
    assertThat(rows.prices().getFirst().priceAmount()).isEqualByComparingTo("2.90");
  }

  @Test
  void discountLineGivesRegularAndClubPrice() {
    var rows = ReceiptReviewService.priceRows(line("2", QuantityUnit.PCS, "24.90", "49.80", "10.00", PriceKind.CLUB_CARD));
    assertThat(rows.prices()).extracting(ObservationPriceInput::priceKind)
        .containsExactly(PriceKind.REGULAR, PriceKind.CLUB_CARD);
    assertThat(rows.prices().get(1).priceAmount()).isEqualByComparingTo("19.90");
  }

  @Test
  void discountWithoutExplicitKindIsPromo() {
    var rows = ReceiptReviewService.priceRows(line("1", QuantityUnit.PCS, "20.00", "20.00", "5.00", PriceKind.REGULAR));
    assertThat(rows.prices()).extracting(ObservationPriceInput::priceKind)
        .containsExactly(PriceKind.REGULAR, PriceKind.PROMO);
  }

  @Test
  void lineWithoutAnyPriceIsRejected() {
    assertThatThrownBy(() -> ReceiptReviewService.priceRows(line("1", QuantityUnit.PCS, null, null, null, PriceKind.REGULAR)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void weighedLineWithoutQuantityDoesNotTurnPaidAmountIntoPricePerKg() {
    // Zaplacených 20,50 Kč za neznámé množství není 20,50 Kč/kg.
    assertThatThrownBy(() -> ReceiptReviewService.priceRows(line(null, QuantityUnit.KG, null, "20.50", null, PriceKind.REGULAR)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void weighedLineWithoutQuantityKeepsPrintedPricePerKgAndSkipsDiscount() {
    var rows = ReceiptReviewService.priceRows(line(null, QuantityUnit.KG, "44.90", "20.50", "3.00", PriceKind.CLUB_CARD));
    assertThat(rows.basis()).isEqualTo(QuantityBasis.PER_KG);
    assertThat(rows.prices()).singleElement().satisfies(p -> {
      assertThat(p.priceKind()).isEqualTo(PriceKind.REGULAR);
      assertThat(p.priceAmount()).isEqualByComparingTo("44.90");
    });
  }

  @Test
  void packageWithoutQuantityIsOnePiece() {
    var rows = ReceiptReviewService.priceRows(line(null, QuantityUnit.PCS, null, "24.90", null, PriceKind.REGULAR));
    assertThat(rows.prices().getFirst().priceAmount()).isEqualByComparingTo("24.90");
  }

  @Test
  void icoFromReceiptDropsVatPrefix() {
    assertThat(ReceiptMatchingService.normalizeIco("DIČ: CZ 44012373")).isEqualTo("44012373");
    assertThat(ReceiptMatchingService.normalizeIco("abc")).isNull();
    assertThat(ReceiptMatchingService.searchableLabel("TUK.ROHLIK 43G")).isEqualTo("TUK ROHLIK 43G");
  }

  private static ReceiptLine line(String qty, QuantityUnit unit, String unitPrice, String total, String discount,
      PriceKind kind) {
    return ReceiptLine.builder()
        .labelRaw("X")
        .quantity(qty == null ? null : new BigDecimal(qty))
        .quantityUnit(unit)
        .unitPrice(unitPrice == null ? null : new BigDecimal(unitPrice))
        .lineTotal(total == null ? null : new BigDecimal(total))
        .discountAmount(discount == null ? null : new BigDecimal(discount))
        .priceKindGuess(kind)
        .build();
  }
}
