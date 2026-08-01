package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.QuantityBasis;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record SubmitObservationInput(
    Long productId,
    Long storeId,
    BigDecimal priceAmount,
    PriceKind priceKind,
    QuantityBasis quantityBasis,
    Integer multibuyQty,
    BigDecimal multibuyTotal,
    OffsetDateTime observedAt) {
}
