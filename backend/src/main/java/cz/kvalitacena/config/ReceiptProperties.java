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
  /**
   * Automatický odhad zboží k řádku (ReceiptMatchingService, match_source FUZZY): nejlepší
   * kandidát musí mít aspoň tuhle podobnost názvu...
   */
  private double autoMatchSimilarity = 0.75;
  /** ...a být o tolik lepší než druhý — jinak je to hádání mezi dvěma podobnými. */
  private double autoMatchMargin = 0.2;
  /**
   * Zboží z účtenky (docs/reputace.md, „Zboží z účtenky"): kolik nepotvrzených (DRAFT) položek
   * z účtenek smí mít jeden účet otevřených naráz. Vlastní strop místo limitů ručního zakládání
   * (app.catalog.*) — jedna velká účtenka by je vyčerpala celé. Co se nevejde, zůstane
   * v přehledu účtenky a zveřejní se později.
   */
  private int maxOpenItems = 300;
  /** Kolik různých lidí musí hlasovat pro stejný cíl, než se zboží z účtenky sloučí i s cizími cenami. */
  private int itemMergeConfirmations = 2;
  /**
   * Řádky, které nejsou zboží (záloha, vratný obal) — regulární výraz nad {@code label_norm}
   * (malá písmena bez diakritiky). Zboží z účtenky se pro ně nezakládá.
   */
  private String nonProductPattern = "\\b(zaloha|zal|zalohy|vratn\\w*|vraceni|vratka)\\b";
  /** Kam hlásit čekající účtenky (ReceiptQueueNotifier); prázdné = notifikace vypnuté. */
  private String notifyEmail;
  private String notifyReminderCron = "0 0 8 * * *";
  private String notifyReminderZone = "Europe/Prague";
}
