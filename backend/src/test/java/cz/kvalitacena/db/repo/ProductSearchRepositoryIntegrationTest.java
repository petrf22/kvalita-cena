package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.service.GtinNormalization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code searchProducts} umí od "Hledat ceny tohoto zboží" (mobil, PriceEntryScreen) najít
 * zboží i podle čárového kódu, ne jen podle názvu — viz {@link ProductSearchRepositoryImpl}
 * (třetí větev {@code matched} CTE) a {@code ProductSearchService.codeQuery}. Nativní SQL, které
 * Mockito neověří, proto Testcontainers jako {@link PriceObservationRepositoryIntegrationTest}.
 *
 * <p>Klíčová regrese, kterou má tenhle test hlídat: hledání podle kódu smí najít jen
 * {@code code_type = GTIN}, NIKDY {@code STORE_INTERNAL} — ten je vnitroobchodní a platí jen
 * v rámci jednoho řetězce (docs/datovy-model.md), takže shoda napříč obchody by byla nesmyslná.
 */
@Testcontainers
@SpringBootTest
class ProductSearchRepositoryIntegrationTest {

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
  private ProductRepository productRepository;
  @Autowired
  private ProductCodeRepository productCodeRepository;
  @Autowired
  private CategoryRepository categoryRepository;
  @Autowired
  private RetailChainRepository retailChainRepository;

  private Category persistCategory() {
    return categoryRepository.saveAndFlush(
        Category.builder().name("Test kategorie " + UUID.randomUUID()).slug("test-" + UUID.randomUUID()).path("test").build());
  }

  private Product persistProduct(Category category, String name) {
    return productRepository.saveAndFlush(Product.builder()
        .name(name)
        .category(category)
        .unitBase(UnitBase.MASS)
        .netContentBase(BigDecimal.ONE)
        .status(ProductStatus.ACTIVE)
        .build());
  }

  /** Náhodných 13 číslic — testu stačí unikátní numerický řetězec, ne platný EAN-13 kontrolní součet. */
  private String randomCode() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 13; i++) sb.append(ThreadLocalRandom.current().nextInt(10));
    return sb.toString();
  }

  private ProductSearchCriteria criteriaByCode(String codeQuery) {
    return new ProductSearchCriteria("", codeQuery, null, null, "CZ", ProductSort.REPORT_COUNT, 20, 0, null);
  }

  @Test
  void searchByCodeFindsProductByGtin() {
    Category category = persistCategory();
    Product product = persistProduct(category, "Search Test GTIN " + UUID.randomUUID());
    String rawCode = randomCode();
    String gtin14 = GtinNormalization.toGtin14(rawCode);
    productCodeRepository.saveAndFlush(ProductCode.builder()
        .product(product).code(gtin14).codeType(CodeType.GTIN).build());

    List<ProductSearchRow> rows = productRepository.search(criteriaByCode(gtin14));

    assertThat(rows).extracting(ProductSearchRow::productId).contains(product.getId());
  }

  @Test
  void searchByCodeIgnoresStoreInternalCode() {
    Category category = persistCategory();
    Product product = persistProduct(category, "Search Test STORE_INTERNAL " + UUID.randomUUID());
    RetailChain chain = retailChainRepository.saveAndFlush(RetailChain.builder()
        .name("Test řetězec " + UUID.randomUUID())
        .slug("test-chain-" + UUID.randomUUID())
        .chainType(ChainType.CHAIN)
        .country("CZ")
        .build());
    String rawCode = randomCode();
    String gtin14 = GtinNormalization.toGtin14(rawCode);
    // Stejná hodnota kódu jako u GTIN v testu výš, ale STORE_INTERNAL — nesmí se najít.
    productCodeRepository.saveAndFlush(ProductCode.builder()
        .product(product).code(gtin14).codeType(CodeType.STORE_INTERNAL).chain(chain).build());

    List<ProductSearchRow> rows = productRepository.search(criteriaByCode(gtin14));

    assertThat(rows).extracting(ProductSearchRow::productId).doesNotContain(product.getId());
  }

  @Test
  void searchWithoutCodeQueryStillMatchesByName() {
    Category category = persistCategory();
    String uniqueName = "Search Test Fulltext " + UUID.randomUUID();
    Product product = persistProduct(category, uniqueName);

    ProductSearchCriteria criteria =
        new ProductSearchCriteria(uniqueName, null, null, null, "CZ", ProductSort.REPORT_COUNT, 20, 0, null);
    List<ProductSearchRow> rows = productRepository.search(criteria);

    assertThat(rows).extracting(ProductSearchRow::productId).contains(product.getId());
  }
}
