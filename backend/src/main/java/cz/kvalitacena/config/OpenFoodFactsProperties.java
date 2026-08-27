package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Konfigurace čtecí integrace Open Food Facts API a lokálního snapshotu v {@code off.*}. */
@Component
@ConfigurationProperties(prefix = "app.external.open-food-facts")
@Data
public class OpenFoodFactsProperties {
  private boolean enabled = true;
  private String baseUrl;
  private String productUrlTemplate;
  private String userAgent;
  private Duration timeout;
  private Duration positiveCacheTtl;
  private Duration negativeCacheTtl;
  private int maxRequestsPerMinute = 15;
}
