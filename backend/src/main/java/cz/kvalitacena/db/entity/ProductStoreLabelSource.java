package cz.kvalitacena.db.entity;

/** Kdo naučil označení z účtenky (core.product_store_label.source). */
public enum ProductStoreLabelSource {
  /** Potvrzený řádek účtenky, ze kterého vznikla cena (ProductStoreLabelService.learnFromLine). */
  USER,
  /**
   * Provozovatel v curate.py (LabelCurationService) — platí hned, bez potvrzení řádku. Vědomá
   * výjimka z „učí se jen z potvrzeného řádku" (docs/rozvoj.md); uživatelský hlas ho nepřebije.
   */
  OPERATOR
}
