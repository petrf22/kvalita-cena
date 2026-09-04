package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Limity zakládání katalogu (obchody, zboží) — docs/reputace.md, "Limity patří do
 * konfigurace, ne natvrdo do kódu". V MVP nastavené velkoryse ze stejného důvodu jako
 * ostatní limity odstupňování přístupu: studený start je větší riziko než zneužití.
 */
@Component
@ConfigurationProperties(prefix = "app.catalog")
@Data
public class CatalogProperties {
  private int maxStoresPerDay;
  private int maxProductsPerDay;
  /** Kolik různých přispěvatelů musí bezkódovou (DRAFT) položku potvrdit, než se stane ACTIVE. */
  private int draftConfirmations;
  /** Kolik různých účtů musí variantu názvu použít s cenou, než vstoupí do veřejného hledání. */
  private int aliasConfirmations;
}
