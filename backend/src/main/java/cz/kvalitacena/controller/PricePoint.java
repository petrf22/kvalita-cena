package cz.kvalitacena.controller;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code convertedUnitPrice}/{@code convertedPriceAmount} použijí kurz PLATNÝ K {@code day},
 * ne dnešní (docs/lokalizace.md) — jinak by graf v USD mísil pohyb ceny s pohybem kurzu.
 */
public record PricePoint(LocalDate day, BigDecimal priceAmount, BigDecimal unitPrice, int nObs, int storeCount,
                          BigDecimal convertedUnitPrice, BigDecimal convertedPriceAmount) {
}
