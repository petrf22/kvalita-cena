package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.QuantityBasis;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Hlavička dávky (docs/datovy-model.md, "core.price_observation je jádro aplikace") — co, kde
 * a kdy jsou pro všechny ceny z jedné cenovky společné, jednotlivé ceny nese {@link #prices()}.
 *
 * <p>{@link #clientSubmissionId()} je náhodné UUID jednoho odeslání, které klient při opakování
 * pošle znovu — server pak dávku nezapíše podruhé (PriceObservationService.submit).
 */
public record SubmitObservationsInput(
    Long productId,
    Long storeId,
    QuantityBasis quantityBasis,
    OffsetDateTime observedAt,
    String currency,
    String productAlias,
    List<ObservationPriceInput> prices,
    UUID clientSubmissionId) {
}
