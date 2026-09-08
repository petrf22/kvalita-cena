package cz.kvalitacena.db.entity;

/** Kde je vytěžená účtenka na cestě od nahrání k zapsaným cenám. */
public enum ReceiptStatus {
  /** Nahraná a rozebraná, párování na katalog ještě neproběhlo. */
  IMPORTED,
  /** Kaskáda párování doběhla; řádky čekají na potvrzení člověkem. */
  MATCHED,
  /** Člověk účtenku potvrdil a z potvrzených řádků vznikly cenové zápisy. */
  CONFIRMED,
  /** Účtenka byla zahozena (rozsypané OCR, omyl) — řádky se nezapisují. */
  DISCARDED
}
