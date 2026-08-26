package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.PriceKind;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Jeden řádek formuláře „druh ceny + částka" (docs/datovy-model.md). {@code priceAmount} je
 * povinná pro všechny druhy kromě {@link PriceKind#MULTIBUY}, kde se odvodí z
 * {@code multibuyTotal} — viz {@code PriceObservationService.submit()}.
 *
 * <p>{@code promoValidFrom}/{@code promoValidTo} smí být vyplněné jen u {@link PriceKind#PROMO}
 * — zapisuje se platnost ceny, kterou uživatel VIDĚL v regále, {@code promoValidFrom} proto
 * nesmí být v budoucnu (na rozdíl od ceny z letáku, docs/rozvoj.md).
 */
public record ObservationPriceInput(
    PriceKind priceKind,
    BigDecimal priceAmount,
    Integer multibuyQty,
    BigDecimal multibuyTotal,
    LocalDate promoValidFrom,
    LocalDate promoValidTo) {
}
