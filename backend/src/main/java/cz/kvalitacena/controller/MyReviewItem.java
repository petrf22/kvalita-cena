package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Product;

import java.time.OffsetDateTime;

/**
 * "Moje recenze" (Query.myReviews) — na rozdíl od {@link ReviewItem} nese i skryté (moderací)
 * vlastní recenze, ať autor vidí, že a proč zmizela z produktu (docs/reputace.md, "Moderace").
 */
public record MyReviewItem(Product product, int stars, String text, OffsetDateTime createdAt,
    OffsetDateTime updatedAt, boolean hidden) {
}
