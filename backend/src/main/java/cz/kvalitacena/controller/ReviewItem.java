package cz.kvalitacena.controller;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Jedna položka výpisu {@code Query.productReviews} (GraphQL typ {@code ProductReview}) —
 * pojmenováno jinak než entita {@link cz.kvalitacena.db.entity.ProductReview}, ať jde v jednom
 * souboru odlišit řádek DB od hotového pohledu pro klienta (stejný důvod jako {@link Photo}
 * vs. entita {@code Media}). {@code text} je tu vždy vyplněný — seznam nese jen recenze
 * S TEXTEM (2026-09-01/03-product-review-text.yaml, {@code idx_product_review_listing}).
 */
public record ReviewItem(Long id, int stars, String text, UUID authorPublicUid, String authorName,
    OffsetDateTime createdAt, OffsetDateTime updatedAt, boolean mine) {
}
