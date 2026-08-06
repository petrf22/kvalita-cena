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
}
