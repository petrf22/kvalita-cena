package cz.kvalitacena.db.entity;

/**
 * Typ globálního záznamu, který lze nahlásit (core.record_flag) — viz {@link RecordFlag}.
 * PHOTO přibylo spolu s {@link Media}: fotka se nahlašuje stejným kanálem jako zboží/obchod,
 * jen s mnohem nižším prahem (docs/reputace.md, {@code app.moderation.photo-flags-to-hide}).
 * REVIEW přibylo spolu s textem recenze ({@code core.product_review.text}) — nahlašuje se
 * TEXT, ne autor (stejné pravidlo jako u zbytku enumu, "žádné veřejné negativní hodnocení
 * uživatelů" níže), práh je mezi fotkou (nejnižší) a katalogem
 * ({@code app.moderation.review-flags-to-hide}). USER je vlastníkem typu jen pro
 * {@link Media} (avatar profilu) — core.record_flag ho nepoužívá, nahlašování uživatelů
 * zůstává mimo tento projekt (docs/reputace.md, "žádné veřejné negativní hodnocení
 * uživatelů").
 */
public enum RecordType {
  PRODUCT,
  STORE,
  PHOTO,
  REVIEW,
  USER
}
