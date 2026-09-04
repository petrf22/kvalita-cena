package cz.kvalitacena.db.entity;

/** Rozsah identity produktu: EAN globálně, bezkódové zboží u prodejce nebo provozovny. */
public enum ProductScope {
  GLOBAL,
  CHAIN,
  STORE,
  /** Starší druhová položka, jejíž původní obchod nelze z existujících dat určit. */
  LEGACY_GLOBAL
}
