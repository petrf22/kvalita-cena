package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Product;

/**
 * Nahlášená recenze v moderátorské frontě ({@link FlaggedRecordItem}) — na rozdíl od
 * veřejného {@link ReviewItem} nese {@code product} (kontext, ke kterému text patří), ne
 * jméno autora (to nese {@code FlaggedRecordItem.authorHandle}, stejně jako u ostatních typů).
 */
public record FlaggedReview(Long id, int stars, String text, Product product) {
}
