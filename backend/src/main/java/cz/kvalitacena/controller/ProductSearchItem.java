package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.Store;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Řádek seznamu hledání — agregáty spočítané spolu s hledáním, respektují filtr obchod/město. */
public record ProductSearchItem(
    Product product,
    int observationCount,
    BigDecimal bestPrice,
    BigDecimal bestUnitPrice,
    Store cheapestStore,
    Integer bestPriceObservations,
    OffsetDateTime lastObservedAt,
    BigDecimal qualityAverage,
    int qualityCount) {
}
