package cz.kvalitacena.db.entity;

/** Odkud se vzalo zboží u řádku účtenky. */
public enum ReceiptMatchSource {
  /** Vnitroobchodní kód → core.product_code (STORE_INTERNAL + chain_id). */
  CODE,
  /** Známé označení → core.product_store_label. */
  LABEL,
  /** Uživatel vybral z našeptávače. */
  USER,
  /** Uživatel pro řádek založil nové zboží. */
  NEW_PRODUCT
}
