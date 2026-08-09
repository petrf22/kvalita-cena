package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.Store;

import java.util.List;

/** {@code currency} je VŽDY vyplněná (docs/lokalizace.md) — graf tím ví, čím popsat osu. */
public record PriceHistory(PriceKind priceKind, Store store, int days, String currency, List<PricePoint> points) {
}
