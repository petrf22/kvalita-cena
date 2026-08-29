package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Šablony URL a text atribuce pro odkazy do cizích otevřených databází. Server sem NIKDY
 * nestahuje data — jen skládá odkaz a posílá povinný text zdroje/licence (docs/datovy-model.md,
 * ODbL).
 */
@Component
@ConfigurationProperties(prefix = "app.external")
@Data
public class ExternalLinkProperties {
  private OpenFoodFacts openFoodFacts = new OpenFoodFacts();

  @Data
  public static class OpenFoodFacts {
    private String productUrlTemplate;
    // {tag} je additives_tags hodnota z OFF, např. "e330" — viz ProductGraphQlController.
    private String additiveUrlTemplate;
  }
}
