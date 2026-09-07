package cz.kvalitacena.db.entity;

/**
 * Čím se párování řádku na katalogovou položku podepřelo — kaskáda z docs/rozvoj.md,
 * „Mapování obchodního označení zboží na katalogovou položku". První dvě jsou strojově jisté,
 * {@code SIMILARITY} je jen návrh, {@code MANUAL} zvolil člověk.
 */
public enum ReceiptMatchSource {
  /** Vnitroobchodní kód z řádku ({@code core.product_code}, STORE_INTERNAL + chain_id). */
  CODE,
  /** Přesná shoda označení v rozsahu ({@code core.product_store_label}). */
  LABEL,
  /** Podobnost přes pg_trgm proti označením, aliasům a názvům zboží. */
  SIMILARITY,
  MANUAL
}
