package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Práh pro zobrazení známky kvality jako "orientační" (obdoba n_eff < 2 u cen, docs/reputace.md). */
@Component
@ConfigurationProperties(prefix = "app.quality")
@Data
public class QualityProperties {
  private int minRatingsForBadge;
}
