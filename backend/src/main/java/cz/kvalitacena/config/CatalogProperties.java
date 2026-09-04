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
  /**
   * Kolik bezkódových položek smí jeden účet založit v JEDNÉ provozovně za den. Rozsah lokální
   * položky je jeden obchod, takže i škoda je lokální — strop na obchod ji drží tam, kde
   * vznikla, aniž by omezil někoho, kdo poctivě zapisuje z víc obchodů.
   */
  private int maxProductsPerStorePerDay;
  /**
   * Kolik nepotvrzených (DRAFT) bezkódových položek smí mít jeden účet otevřených naráz.
   * Na rozdíl od denního stropu tohle škodiči zavírá škálování časem — dokud mu nikdo ani
   * jednu položku nepotvrdí zápisem ceny, další nezaloží. Poctivého přispěvatele neomezí,
   * jeho položky se potvrzují (docs/reputace.md, "Zboží bez čárového kódu").
   */
  private int maxUnconfirmedDrafts;
  /** Kolik různých přispěvatelů musí bezkódovou (DRAFT) položku potvrdit, než se stane ACTIVE. */
  private int draftConfirmations;
  /** Kolik různých účtů musí variantu názvu použít s cenou, než vstoupí do veřejného hledání. */
  private int aliasConfirmations;
  /**
   * Práh podobnosti názvu pro našeptávač a kontrolu duplicit (pg_trgm, 0–1). Nižší číslo
   * nabídne víc a riskuje šum, vyšší nechá vzniknout duplicitu — proto konfigurace, ne
   * konstanta v dotazu.
   */
  private double suggestionSimilarity;
  /**
   * Od jaké podobnosti názvu se dvojice lokálních položek nabídne moderátorovi jako podezření
   * na duplicitu. Výrazně vyšší než {@link #suggestionSimilarity} — našeptávač si může dovolit
   * nabídnout i vzdálenou shodu, fronta duplicit ne, ta by se jinak zaplnila šumem
   * ("dršťková" vs. "gulášová polévka"). Slučuje se VŽDY ručně, tohle je jen řazení fronty.
   */
  private double duplicateSimilarity;
}
