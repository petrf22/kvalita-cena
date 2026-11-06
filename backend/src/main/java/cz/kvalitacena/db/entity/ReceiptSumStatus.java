package cz.kvalitacena.db.entity;

/**
 * Kontrolní součet workeru — součet položek proti vytištěnému „Celkem" (tools/uctenky/parse.py).
 * Řídí jen předvýběr řádků ke zveřejnění v klientovi, nic serverově nezakazuje.
 */
public enum ReceiptSumStatus {
  /** Součet sedí v toleranci workeru. */
  MATCH,
  /** Součet nesedí — OCR nejspíš špatně přečetlo cenu nebo vynechalo řádek. */
  MISMATCH,
  /** Celkem se na účtence nenašlo, nebo účtenku zpracovala starší verze workeru. */
  UNKNOWN
}
