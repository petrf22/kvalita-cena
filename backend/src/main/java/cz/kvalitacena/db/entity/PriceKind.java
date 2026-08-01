package cz.kvalitacena.db.entity;

/** Akční, klubová a běžná cena se nikdy nemíchají do jedné řady — viz docs/datovy-model.md. */
public enum PriceKind {
  REGULAR,
  PROMO,
  CLUB_CARD,
  CLEARANCE,
  MULTIBUY
}
