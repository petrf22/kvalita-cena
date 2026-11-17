package cz.kvalitacena.db.entity;

/**
 * Filtr seznamu vlastních účtenek ({@code myReceipts}). Skupiny se nepřekrývají a dohromady dávají
 * {@link #ALL}; podmínky v SQL jsou jen v {@code ReceiptRepository} (stránka i počty z jedné
 * konstanty, ať se nerozejdou).
 */
public enum ReceiptListFilter {
  ALL,
  /** Zpracovaná, ale párování ještě není hotové — čeká na uživatele. */
  TO_REVIEW,
  /** Ve frontě nebo ji právě čte worker. */
  PROCESSING,
  /** Zpracovaná a spárovaná. */
  DONE,
  /** Stroj skončil bez řádků (není účtenka, nečitelná, selhání) — zbývá nafotit znovu nebo smazat. */
  FAILED
}
