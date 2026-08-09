package cz.kvalitacena.db.repo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Jeden řádek výsledku {@link ProductSearchRepository#search}, agregáty v rozsahu filtru. */
public record ProductSearchRow(
    Long productId,
    long observationCount,
    BigDecimal bestPrice,
    BigDecimal bestUnitPrice,
    Long cheapestStoreId,
    Integer bestPriceObservations,
    OffsetDateTime lastObservedAt,
    BigDecimal qualityAverage,
    long qualityCount,
    String currency) {
}
