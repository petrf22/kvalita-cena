package cz.kvalitacena.db.repo;

/** Řazení výsledků hledání — hodnoty musí odpovídat enumu {@code ProductSort} v schema.graphqls. */
public enum ProductSort {
  /** Výchozí — nejvíc potvrzené zboží nahoře (součet agg.price_current.n_obs). */
  REPORT_COUNT,
  PRICE_ASC,
  /** 5 = nejlepší, tedy sestupně. */
  QUALITY,
  LAST_REPORTED,
  NAME
}
