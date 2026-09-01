package cz.kvalitacena.controller;

import java.time.OffsetDateTime;

/**
 * Návratový typ {@code saveProductReviewText}/{@code deleteProductReviewText} — vlastní
 * hodnocení včetně textu, ať klient po úpravě/smazání nemusí přenačítat celý produkt.
 * {@code text} je {@code null} po smazání nebo pokud ho autor nikdy nenapsal.
 */
public record MyProductReview(int stars, String text, OffsetDateTime updatedAt) {
}
