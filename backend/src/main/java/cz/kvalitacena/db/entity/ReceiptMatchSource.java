package cz.kvalitacena.db.entity;

/** Odkud se vzalo zboží u řádku účtenky. */
public enum ReceiptMatchSource {
  /** Vnitroobchodní kód → core.product_code (STORE_INTERNAL + chain_id). */
  CODE,
  /** Známé označení → core.product_store_label. */
  LABEL,
  /**
   * Jednoznačná podobnost čitelného názvu se zbožím v katalogu (ReceiptMatchingService) —
   * jen odhad: klient ho ukáže označený a nezaškrtne předem, potvrdit ho musí uživatel.
   */
  FUZZY,
  /** Uživatel vybral z našeptávače. */
  USER,
  /** Uživatel pro řádek založil nové zboží. */
  NEW_PRODUCT
}
