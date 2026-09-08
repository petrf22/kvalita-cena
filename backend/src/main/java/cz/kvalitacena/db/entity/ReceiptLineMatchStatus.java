package cz.kvalitacena.db.entity;

/**
 * Jak daleko je řádek na cestě ke katalogové položce. {@code MATCHED} znamená strojově jistou
 * shodu (kód nebo přesné označení), {@code SUGGESTED} jen návrh k potvrzení — model ani
 * podobnost nikdy nepotvrzuje sama (docs/ai.md, „AI nikdy nerozhoduje").
 */
public enum ReceiptLineMatchStatus {
  UNMATCHED,
  SUGGESTED,
  MATCHED,
  /** Člověk řádek vyřadil z importu (nezajímavý, nečitelný). */
  SKIPPED,
  /** Z řádku vznikl cenový zápis. */
  IMPORTED
}
