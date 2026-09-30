package cz.kvalitacena.db.entity;

/** Co s účtenkou udělal stroj (docs/datovy-model.md, „Účtenky") — nezávislé na {@link ReceiptReviewStatus}. */
public enum ReceiptProcessingStatus {
  QUEUED,
  PROCESSING,
  PROCESSED,
  /** Worker poznal, že obrázek účtenka není — nic dalšího se neděje. */
  NOT_A_RECEIPT,
  /** Účtenka, ale nečitelná (rozmazaná, oříznutá) — uživatel ji může nafotit znovu. */
  UNREADABLE,
  /** Worker opakovaně selhal (app.receipt.max-attempts). */
  FAILED;

  public boolean isFinished() {
    return this != QUEUED && this != PROCESSING;
  }
}
