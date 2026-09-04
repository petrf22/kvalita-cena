package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nabídka zboží pro bezkódový zápis ceny — {@code findLocalByStore} (procházení nabídky jedné
 * provozovny bez psaní) a {@code findSimilarByName} (podobnostní hledání). Obojí je nativní SQL
 * nad pg_trgm, které Mockito neověří, proto Testcontainers jako
 * {@link ProductSearchRepositoryIntegrationTest}.
 *
 * <p>Regrese, které tenhle test hlídá: {@code word_similarity} musí najít dlouhý název podle
 * jednoho slova (samotná {@code similarity} takovou shodu utopí v délce názvu); lokální nabídka
 * nesmí přetéct z jiné provozovny ani vytáhnout globální zboží; a nepotvrzené (DRAFT) položky
 * se řadí až za potvrzené, přestože zůstávají viditelné (docs/reputace.md, "Zboží bez čárového
 * kódu" — jinak by je neměl kdo potvrdit).
 */
@Testcontainers
@SpringBootTest
class ProductSuggestionRepositoryIntegrationTest {

  /** Stejná hodnota jako app.catalog.suggestion-similarity v application.yml. */
  private static final double THRESHOLD = 0.2;

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
  private StoreRepository storeRepository;
  @Autowired
  private CategoryRepository categoryRepository;
  @Autowired
  private PriceObservationRepository priceObservationRepository;

  private Category persistCategory() {
    String suffix = UUID.randomUUID().toString();
    return categoryRepository.saveAndFlush(Category.builder()
        .name("Test kategorie " + suffix).slug("test-" + suffix).path("test-" + suffix).build());
  }

  private Store persistStore() {
    return storeRepository.saveAndFlush(Store.builder()
        .name("Test obchod " + UUID.randomUUID())
        .city("Praha")
        .country("CZ")
        .status(StoreStatus.ACTIVE)
        .build());
  }

  /** Bezkódová položka vázaná na jednu provozovnu — obchod bez řetězce dává rozsah STORE. */
  private Product persistLocalProduct(Category category, Store store, String name, ProductStatus status) {
    return productRepository.saveAndFlush(Product.builder()
        .name(name)
        .category(category)
        .unitBase(UnitBase.MASS)
        .netContentBase(BigDecimal.ONE)
        .status(status)
        .generic(true)
        .catalogScope(ProductScope.STORE)
        .scopeStore(store)
        .build());
  }

  private void persistObservation(Product product, Store store) {
    priceObservationRepository.saveAndFlush(PriceObservation.builder()
        .product(product)
        .store(store)
        .priceAmount(BigDecimal.TEN)
        .currency("CZK")
        .priceKind(PriceKind.REGULAR)
        .quantityBasis(QuantityBasis.PACKAGE)
        .netContentBase(BigDecimal.ONE)
        .observedAt(OffsetDateTime.now())
        .submitterKind(SubmitterKind.ANONYMOUS)
        .source(ObservationSource.WEB)
        .build());
  }

  @Test
  void localOfferListsOnlyProductsOfThatStore() {
    Category category = persistCategory();
    Store store = persistStore();
    Store otherStore = persistStore();
    Product mine = persistLocalProduct(category, store, "Dršťková polévka " + UUID.randomUUID(), ProductStatus.ACTIVE);
    Product foreign = persistLocalProduct(category, otherStore, "Gulášová polévka " + UUID.randomUUID(), ProductStatus.ACTIVE);

    List<Product> offer = productRepository.findLocalByStore(store.getId(), 20);

    assertThat(offer).extracting(Product::getId).contains(mine.getId()).doesNotContain(foreign.getId());
  }

  @Test
  void localOfferPutsMostOftenPricedFirstAndDraftsLast() {
    Category category = persistCategory();
    Store store = persistStore();
    Product rare = persistLocalProduct(category, store, "Zřídka kupovaná položka " + UUID.randomUUID(), ProductStatus.ACTIVE);
    Product popular = persistLocalProduct(category, store, "Často kupovaná položka " + UUID.randomUUID(), ProductStatus.ACTIVE);
    Product draft = persistLocalProduct(category, store, "Nepotvrzená položka " + UUID.randomUUID(), ProductStatus.DRAFT);
    persistObservation(rare, store);
    persistObservation(popular, store);
    persistObservation(popular, store);
    // DRAFT má nejvíc zápisů, a přesto musí skončit poslední — pořadí rozhoduje nejdřív
    // potvrzenost, teprve pak popularita.
    persistObservation(draft, store);
    persistObservation(draft, store);
    persistObservation(draft, store);

    List<Product> offer = productRepository.findLocalByStore(store.getId(), 20);

    assertThat(offer).extracting(Product::getId)
        .containsExactly(popular.getId(), rare.getId(), draft.getId());
  }

  @Test
  void similarNameMatchesLongNameByASingleWord() {
    Category category = persistCategory();
    Store store = persistStore();
    Product soup = persistLocalProduct(category, store,
        "Dršťková polévka s kroupami a majoránkou", ProductStatus.ACTIVE);

    // Samotná similarity() poměřuje shodu vůči trigramům CELÉHO názvu, takže jedno slovo
    // z dlouhého názvu spadne pod práh; word_similarity() hledá jen v nejlepším úseku.
    List<Product> matches = productRepository.findSimilarByName("polevku", store.getId(), null, THRESHOLD, 20);

    assertThat(matches).extracting(Product::getId).contains(soup.getId());
  }

  @Test
  void similarNameSortsDraftsAfterConfirmedOnes() {
    Category category = persistCategory();
    Store store = persistStore();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Product draft = persistLocalProduct(category, store, "Dršťková polévka " + suffix, ProductStatus.DRAFT);
    Product active = persistLocalProduct(category, store, "Dršťková polévka silná " + suffix, ProductStatus.ACTIVE);

    List<Product> matches = productRepository.findSimilarByName("dršťková polévka", store.getId(), null, THRESHOLD, 20);

    assertThat(matches).extracting(Product::getId)
        .containsSubsequence(active.getId(), draft.getId());
  }
}
