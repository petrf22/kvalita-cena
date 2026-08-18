package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.PriceKind;

import java.math.BigDecimal;

/**
 * Jeden řádek formuláře „druh ceny + částka" (docs/datovy-model.md). {@code priceAmount} je
 * povinná pro všechny druhy kromě {@link PriceKind#MULTIBUY}, kde se odvodí z
 * {@code multibuyTotal} — viz {@code PriceObservationService.submit()}.
 */
public record ObservationPriceInput(
    PriceKind priceKind,
    BigDecimal priceAmount,
    Integer multibuyQty,
    BigDecimal multibuyTotal) {
}
