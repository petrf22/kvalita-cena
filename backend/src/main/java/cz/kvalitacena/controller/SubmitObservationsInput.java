package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.QuantityBasis;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Hlavička dávky (docs/datovy-model.md, "core.price_observation je jádro aplikace") — co, kde
 * a kdy jsou pro všechny ceny z jedné cenovky společné, jednotlivé ceny nese {@link #prices()}.
 */
public record SubmitObservationsInput(
    Long productId,
    Long storeId,
    QuantityBasis quantityBasis,
    OffsetDateTime observedAt,
    String currency,
    String productAlias,
    List<ObservationPriceInput> prices) {
}
