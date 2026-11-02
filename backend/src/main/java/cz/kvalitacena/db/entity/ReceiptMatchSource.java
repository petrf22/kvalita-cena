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
  /**
   * EAN z řádku katalog nezná, ale zná ho Open Food Facts a dodá vše potřebné k založení
   * (ReceiptMatchingService.offCandidate) — řádek je návrh BEZ zboží, zboží se založí nad OFF
   * snapshotem až při potvrzení (ReceiptReviewService.confirmLine). Deterministické jako CODE,
   * klient ho proto zaškrtne předem.
   */
  OFF,
  /** Uživatel vybral z našeptávače. */
  USER,
  /** Uživatel pro řádek založil nové zboží. */
  NEW_PRODUCT
}
