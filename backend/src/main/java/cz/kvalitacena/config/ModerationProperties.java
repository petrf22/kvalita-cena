package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Nahlašování záznamů (core.record_flag) — hlasuje se o FAKTU, ne o ČLOVĚKU
 * (docs/reputace.md). Prahy patří sem, ne natvrdo do kódu.
 */
@Component
@ConfigurationProperties(prefix = "app.moderation")
@Data
public class ModerationProperties {
  /** Kolik různých nahlášení skryje záznam (RecordFlagService) a pošle ho k přezkumu. */
  private int flagsToHide;
  /**
   * Fotka je nejrizikovější uživatelský obsah v appce (nevhodný obrázek) — cena přehlédnutého
   * nahlášení je vyšší než cena zbytečně skryté dobré fotky (dá se nahrát znovu), proto
   * mnohem nižší práh než u zboží/obchodu (docs/reputace.md).
   */
  private int photoFlagsToHide;
  /**
   * Text recenze je druhý nejrizikovější uživatelský obsah (volný text, ne jen katalogové
   * pole) — práh mezi fotkou (nejnižší) a zbožím/obchodem (docs/reputace.md).
   */
  private int reviewFlagsToHide;
}
