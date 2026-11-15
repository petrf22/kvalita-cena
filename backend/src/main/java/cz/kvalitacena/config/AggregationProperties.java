package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agregace cen (docs/reputace.md, "Váha jednoho cenového záznamu") — poločas {@code f_recency},
 * kterým {@code PriceAggregationService} tlumí starší záznamy v AKTUÁLNÍ ceně
 * ({@code agg.price_current}). Na denní řadu pro graf se neuplatňuje.
 */
@Component
@ConfigurationProperties(prefix = "app.aggregation")
@Data
public class AggregationProperties {
  /** Poločas ve dnech; 0 nebo méně útlum vypíná (všechny záznamy stejně staré). */
  private double recencyHalfLifeDays = 14;
}
