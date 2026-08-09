package cz.kvalitacena.db.entity;

/**
 * Typ globálního záznamu, který lze nahlásit (core.record_flag) — viz {@link RecordFlag}.
 * PHOTO přibylo spolu s {@link Media}: fotka se nahlašuje stejným kanálem jako zboží/obchod,
 * jen s mnohem nižším prahem (docs/reputace.md, {@code app.moderation.photo-flags-to-hide}).
 */
public enum RecordType {
  PRODUCT,
  STORE,
  PHOTO
}
