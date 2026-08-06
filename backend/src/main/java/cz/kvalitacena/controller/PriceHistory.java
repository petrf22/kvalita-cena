package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.Store;

import java.util.List;

public record PriceHistory(PriceKind priceKind, Store store, int days, List<PricePoint> points) {
}
