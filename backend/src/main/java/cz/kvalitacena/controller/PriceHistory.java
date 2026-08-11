package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.Store;

import java.util.List;

/**
 * {@code currency} je VŽDY vyplněná (docs/lokalizace.md) — graf tím ví, čím popsat osu.
 * {@code displayCurrency}/{@code rateAttribution} jsou null, dokud se řada nepřepočítávala
 * (X-Display-Currency), viz {@link PricePoint#convertedUnitPrice}.
 */
public record PriceHistory(PriceKind priceKind, Store store, int days, String currency,
                            String displayCurrency, String rateAttribution, List<PricePoint> points) {
}
