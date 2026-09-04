package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductScope;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.StoreStatus;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reálný PostgreSQL hlídá pořadí přesunů a výrazové unikátní indexy, které Mockito neověří. */
@Testcontainers
@SpringBootTest
class ProductMergeServiceIntegrationTest {

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
  private ProductMergeService mergeService;
  @Autowired
  private ProductRenameService renameService;
  @Autowired
  private ModerationService moderationService;
  @Autowired
  private ProductRepository productRepository;
  @Autowired
  private StoreRepository storeRepository;
  @Autowired
  private CategoryRepository categoryRepository;
  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void mergeMovesObservationsAndAliasesAndLeavesRedirect() {
    Store store = persistStore();
    Product source = persistProduct(store, "Třicátník celý " + UUID.randomUUID());
    Product target = persistProduct(store, "Třicátník " + UUID.randomUUID());
    insertObservation(source.getId(), store.getId());
    jdbcTemplate.update("INSERT INTO core.product_alias(product_id,name,status) VALUES (?,?,'PENDING')",
        source.getId(), "Chléb třicátník");

    Product result = mergeService.merge(source.getId(), target.getId(), 1L);

    assertThat(result.getId()).isEqualTo(target.getId());
    Product merged = productRepository.findWithMergedIntoById(source.getId()).orElseThrow();
    assertThat(merged.getStatus()).isEqualTo(ProductStatus.MERGED);
    assertThat(merged.getMergedInto().getId()).isEqualTo(target.getId());
    assertThat(jdbcTemplate.queryForObject(
        "SELECT count(*) FROM core.price_observation WHERE product_id = ?", Long.class, target.getId()))
        .isEqualTo(1L);
    assertThat(jdbcTemplate.queryForList(
        "SELECT name FROM core.product_alias WHERE product_id = ?", String.class, target.getId()))
        .contains(source.getName(), "Chléb třicátník");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT count(*) FROM agg.recompute_queue WHERE product_id = ? AND store_id = ?",
        Long.class, target.getId(), store.getId())).isEqualTo(1L);
  }

  @Test
  void mergeRejectsObservationOutsideTargetStoreScope() {
    Store targetStore = persistStore();
    Store otherStore = persistStore();
    Product source = persistProduct(otherStore, "Zdroj " + UUID.randomUUID());
    Product target = persistProduct(targetStore, "Cíl " + UUID.randomUUID());
    insertObservation(source.getId(), otherStore.getId());

    assertThatThrownBy(() -> mergeService.merge(source.getId(), target.getId(), 1L))
        .isInstanceOf(ValidationException.class);
    assertThat(productRepository.findById(source.getId()).orElseThrow().getStatus())
        .isEqualTo(ProductStatus.ACTIVE);
  }

  @Test
  void renameKeepsOldNameAsActiveAlias() {
    Store store = persistStore();
    Product product = persistProduct(store, "Polevka drstkova " + UUID.randomUUID());
    String previousName = product.getName();

    Product renamed = renameService.rename(product.getId(), "  Dršťková   polévka  ", 1L);

    assertThat(renamed.getName()).isEqualTo("Dršťková polévka");
    assertThat(productRepository.findById(product.getId()).orElseThrow().getName())
        .isEqualTo("Dršťková polévka");
    // Kdo si pamatuje původní (chybný) název, musí položku dál najít.
    assertThat(jdbcTemplate.queryForList(
        "SELECT name FROM core.product_alias WHERE product_id = ? AND status = 'ACTIVE'",
        String.class, product.getId())).contains(previousName);
  }

  /** Oprava, která mění jen diakritiku/velikost písmen, nesmí založit alias shodný s názvem. */
  @Test
  void renameToTheSameNormalizedNameCreatesNoAlias() {
    Store store = persistStore();
    String suffix = UUID.randomUUID().toString();
    Product product = persistProduct(store, "drstkova polevka " + suffix);

    renameService.rename(product.getId(), "Dršťková polévka " + suffix, 1L);

    assertThat(jdbcTemplate.queryForObject(
        "SELECT count(*) FROM core.product_alias WHERE product_id = ?", Long.class, product.getId()))
        .isZero();
  }

  @Test
  void renameRejectsProductWithBarcode() {
    Store store = persistStore();
    Product product = persistProduct(store, "S kódem " + UUID.randomUUID());
    product.setGeneric(false);
    productRepository.saveAndFlush(product);

    assertThatThrownBy(() -> renameService.rename(product.getId(), "Nový název", 1L))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void duplicateCandidatesPairsSimilarNamesInSameStore() {
    // Obchod i kategorie jsou pro každý test nové, takže názvy mohou být holé bez UUID přípony
    // — a musí být: společná přípona by podobnost všech tří dvojic uměle nafoukla.
    Store store = persistStore();
    Category category = persistCategory();
    Product first = persistProduct(store, category, "Dršťková polévka");
    Product second = persistProduct(store, category, "Dršťková polévka velká");
    // Jiná polévka v témže obchodě a kategorii — textově blízko (0,42), ale jiný výrobek, takže
    // pod prahem 0,55 a nesmí se spárovat (docs/reputace.md, automatické slučování se nedělá).
    Product other = persistProduct(store, category, "Gulášová polévka");

    var result = moderationService.duplicateCandidates(50, 0, 1L);

    assertThat(result.items())
        .anySatisfy(candidate -> assertThat(java.util.Set.of(candidate.left().getId(), candidate.right().getId()))
            .containsExactlyInAnyOrder(first.getId(), second.getId()));
    assertThat(result.items())
        .noneSatisfy(candidate -> assertThat(java.util.Set.of(candidate.left().getId(), candidate.right().getId()))
            .contains(other.getId()));
  }

  private Store persistStore() {
    String suffix = UUID.randomUUID().toString();
    return storeRepository.saveAndFlush(Store.builder()
        .name("Merge obchod " + suffix)
        .city("Praha")
        .country("CZ")
        .status(StoreStatus.ACTIVE)
        .build());
  }

  private Category persistCategory() {
    return categoryRepository.saveAndFlush(Category.builder()
        .name("Merge kategorie " + UUID.randomUUID())
        .slug("merge-" + UUID.randomUUID())
        .path("merge-" + UUID.randomUUID())
        .build());
  }

  private Product persistProduct(Store store, String name) {
    return persistProduct(store, persistCategory(), name);
  }

  private Product persistProduct(Store store, Category category, String name) {
    return productRepository.saveAndFlush(Product.builder()
        .name(name)
        .category(category)
        .unitBase(UnitBase.COUNT)
        .netContentBase(BigDecimal.ONE)
        .generic(true)
        .catalogScope(ProductScope.STORE)
        .scopeStore(store)
        .status(ProductStatus.ACTIVE)
        .build());
  }

  private void insertObservation(Long productId, Long storeId) {
    jdbcTemplate.update("""
        INSERT INTO core.price_observation(
          product_id, store_id, price_amount, currency, price_kind, quantity_basis,
          net_content_base, observed_at, submitter_kind, status, agreement, source)
        VALUES (?, ?, 45, 'CZK', 'REGULAR', 'PACKAGE', 1, CURRENT_TIMESTAMP,
          'ANONYMOUS', 'ACTIVE', 'PENDING', 'WEB')
        """, productId, storeId);
  }
}
