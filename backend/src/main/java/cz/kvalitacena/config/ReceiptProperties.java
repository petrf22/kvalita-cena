package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Účtenky (docs/ai.md, „Účtenky" — tabulka prahů a limitů je tam, ne tady). Hodnoty se mění
 * v application.yml, nikdy natvrdo v kódu.
 */
@Component
@ConfigurationProperties(prefix = "app.receipt")
@Data
public class ReceiptProperties {
  /** Verze textu souhlasu — zvednout při změně zpracovatele nebo účelu (docs/soukromi.md). */
  private int consentVersion = 1;
  private int maxImagesPerReceipt = 30;
  private int maxReceiptsPerDay = 10;
  private long maxImageBytes = 4_194_304;
  /** Šířka uloženého výřezu; výška se neomezuje (jen app.media.max-pixels). */
  private int maxWidth = 1600;
  private int imageRetentionDays = 30;
  private int archiveGraceDays = 14;
  /** Nezpracované účtenky (worker dlouho neběžel) — obrázky se smažou nejpozději po téhle době od nahrání. */
  private int staleImageDays = 90;
  private int leaseMinutes = 15;
  private int maxAttempts = 3;
  private int maxLeaseBatch = 10;
  private int labelConfirmations = 1;
  private int rematchMaxAgeDays = 180;
  private int maxLines = 300;
  /** Práh podobnosti pro našeptávač označení z účtenky (pg_trgm similarity). */
  private double labelSimilarity = 0.35;
  /** Kam hlásit čekající účtenky (ReceiptQueueNotifier); prázdné = notifikace vypnuté. */
  private String notifyEmail;
  private String notifyReminderCron = "0 0 8 * * *";
  private String notifyReminderZone = "Europe/Prague";
}
