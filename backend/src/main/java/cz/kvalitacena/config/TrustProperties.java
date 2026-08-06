package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Práh důvěry pro okamžité zveřejnění nového záznamu (obchod/zboží) — etapa-1 aproximace
 * úrovně T2 z docs/reputace.md (stáří účtu + počet vlastních záznamů), plný vzorec {@code S} je
 * až etapa 2/3. Prahy patří sem, ne natvrdo do kódu (docs/reputace.md, "Limity patří do
 * konfigurace").
 */
@Component
@ConfigurationProperties(prefix = "app.trust")
@Data
public class TrustProperties {
  /** Účet musí být starší než tolik dní. */
  private int minAccountAgeDays;
  /** A mít aspoň tolik vlastních cenových záznamů (auth.app_user.observation_count). */
  private int minObservations;
}
