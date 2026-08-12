package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Retence vazby observace → uživatel (docs/soukromi.md, "Retence vazby observace → uživatel:
 * 180 dní") — {@code PseudonymizationService} po tomhle počtu dní od {@code observed_at}
 * nastaví {@code price_observation.submitter_id} na NULL.
 */
@Component
@ConfigurationProperties(prefix = "app.privacy")
@Data
public class PrivacyProperties {
  private int pseudonymizationDays;
}
