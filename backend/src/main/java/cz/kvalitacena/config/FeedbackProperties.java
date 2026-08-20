package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Zpětná vazba od uživatele appky (core.feedback, docs/nasazeni.md „Než pozvat první lidi").
 * Prahy patří sem, ne natvrdo do kódu (stejná konvence jako {@link ModerationProperties}).
 */
@Component
@ConfigurationProperties(prefix = "app.feedback")
@Data
public class FeedbackProperties {
  /** Strop délky zprávy — appka nemá jinou obranu proti nesmyslně dlouhému vstupu. */
  private int maxMessageLength;
  /** Strop délky volitelně přiloženého stacktrace posledního pádu (mobil). */
  private int maxDiagnosticsLength;
  /** Denní strop na IP — jediná obrana u ANONYMNÍHO odeslání (žádný userId k dispozici). */
  private int maxPerDayPerIp;
  /** Denní strop na přihlášeného uživatele. */
  private int maxPerDayPerUser;
}
