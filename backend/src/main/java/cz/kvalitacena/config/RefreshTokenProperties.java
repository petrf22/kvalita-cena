package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.auth.refresh-token")
@Data
public class RefreshTokenProperties {
  private Duration webTtl;
  private Duration androidTtl;
  /** Opakované použití téhož tokenu do tohoto okna se toleruje (ztracená odpověď na mobilu),
   *  mimo něj se bere jako reuse a revokuje se celá rodina — viz RefreshTokenService. */
  private Duration reuseGracePeriod;
}
