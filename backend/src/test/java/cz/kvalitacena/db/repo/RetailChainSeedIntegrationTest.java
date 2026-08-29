package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.RetailChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI guard nad seedem z {@code db/changelog/2026-08-29/03-retail-chain-seed.yaml}
 * (docs/stav-implementace.md, "výběr řetězce při zakládání obchodu") — stejný vzor jako
 * {@link CategorySeedIntegrationTest}, jen nad číselníkem řetězců místo kategorií.
 *
 * <p>Běží nad reálným Postgresem (Testcontainers), protože ověřuje jak DATA naseedovaná
 * migrací, tak nativní {@code core.norm_text(...) LIKE} dotaz {@link RetailChainRepository},
 * který Mockito ({@link cz.kvalitacena.service.ChainCatalogServiceTest}) neprověří.
 */
@Testcontainers
@SpringBootTest
class RetailChainSeedIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.docker.compose.enabled", () -> false);
  }

  @Autowired
  private RetailChainRepository retailChainRepository;

  @Test
  void seedHasNoDuplicateSlugWithinCountry() {
    List<RetailChain> chains = retailChainRepository.findAll();
    List<String> keys = chains.stream().map(c -> c.getCountry() + "/" + c.getSlug()).toList();
    assertThat(keys).doesNotHaveDuplicates();
  }

  @Test
  void seedContainsWellKnownCzechChains() {
    List<String> names = retailChainRepository.findAll().stream()
        .filter(c -> "CZ".equals(c.getCountry()))
        .map(RetailChain::getName)
        .collect(Collectors.toList());

    assertThat(names).contains("Kaufland", "Lidl", "Albert", "COOP TIP", "Rohlík.cz");
  }

  @Test
  void searchByTextIgnoresDiacriticsAndCase() {
    List<RetailChain> found = retailChainRepository.searchByText("zabka", "CZ", 20);

    assertThat(found).extracting(RetailChain::getName).containsExactly("Žabka");
  }

  @Test
  void searchByTextWithNullQueryReturnsAllChainsOfCountryUpToLimit() {
    List<RetailChain> found = retailChainRepository.searchByText(null, "CZ", 5);

    assertThat(found).hasSize(5);
  }

  @Test
  void searchByTextFiltersOutOtherCountries() {
    List<RetailChain> found = retailChainRepository.searchByText(null, "SK", 20);

    assertThat(found).isEmpty();
  }

  @Test
  void searchByTextMatchesSubstringAcrossMultipleChains() {
    List<RetailChain> found = retailChainRepository.searchByText("coop", "CZ", 20);

    assertThat(found).extracting(RetailChain::getName)
        .contains("COOP TIP", "COOP Tuty", "COOP Terno", "COOP Diskont", "COOP Jednota")
        .doesNotContain("Konzum");
  }
}
