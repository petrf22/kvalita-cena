package cz.kvalitacena.db.entity;

/** Stav párování jednoho řádku účtenky (docs/datovy-model.md, „Účtenky"). */
public enum ReceiptLineStatus {
  UNMATCHED,
  /** Server našel zboží přes kód nebo známé označení — v UI předem zaškrtnuté, cenu zapíše až potvrzení. */
  SUGGESTED,
  /** Uživatel potvrdil (observationId ukazuje na vzniklou cenu, pokud nebyla přeskočena). */
  MATCHED,
  /** Uživatel párovat nechce. */
  IGNORED
}
