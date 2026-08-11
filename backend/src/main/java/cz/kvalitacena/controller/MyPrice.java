package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.Store;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Poslední vlastní zápis přihlášeného uživatele na (produkt, obchod, druh ceny) — zobrazí se
 * vedle komunitního agregátu jako "Vaše cena", a to i dřív, než ho zpracuje
 * PriceAggregationService (běží jen co pár sekund, ale uživatel má vidět svůj zápis okamžitě).
 */
public record MyPrice(Store store, PriceKind priceKind, BigDecimal priceAmount, BigDecimal unitPrice,
                       OffsetDateTime observedAt, String currency, ConvertedPrice converted) {
}
