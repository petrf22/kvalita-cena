package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Limity textu recenze (docs/reputace.md, "Limity patří do konfigurace") — text recenze je
 * první veřejný text od uživatelů v appce, proto vlastní denní strop na zápis, oddělený od
 * {@code app.catalog} (zakládání zboží/obchodů) i {@code app.moderation} (skrývání po
 * nahlášení, RecordType.REVIEW — CatalogRateLimiter řeší jen ZÁPIS, ne moderaci obsahu).
 */
@Component
@ConfigurationProperties(prefix = "app.review")
@Data
public class ReviewProperties {
  /** Max znaků textu recenze (CHECK v core.product_review, 2026-09-01/03-product-review-text.yaml). */
  private int maxTextLength;
  private int maxPerDay;
}
