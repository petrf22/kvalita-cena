package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Odstupňování přístupu k historii ceny podle docs/reputace.md (T0 anonym má nárok jen na
 * kratší okno). Hodnota pro anonyma je v etapě 1 vědomě velkorysejší, než tabulka T0–T4
 * uvádí (7 dní) — studený start je větší riziko než parazitování na datech, viz plán a
 * poznámka v docs/reputace.md.
 */
@Component
@ConfigurationProperties(prefix = "app.history")
@Data
public class PriceHistoryProperties {
  private int maxDays;
  private int anonymousMaxDays;
}
