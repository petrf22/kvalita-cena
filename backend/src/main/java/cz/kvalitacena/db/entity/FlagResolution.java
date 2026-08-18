package cz.kvalitacena.db.entity;

/**
 * Výsledek moderátorského přezkumu nahlášeného záznamu ({@link RecordFlag}) — viz
 * {@code cz.kvalitacena.service.ModerationService}, docs/reputace.md „Moderace (etapa 1)".
 */
public enum FlagResolution {
  /** Nahlášení bylo neopodstatněné — {@code hidden_at} cíle se vrátí na NULL. */
  DISMISSED,
  /** Nahlášení bylo oprávněné — cíl zůstává (nebo se nově nastaví) skrytý. */
  UPHELD
}
