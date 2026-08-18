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

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code countDistinctProductContributorsExcluding}/{@code countDistinctContributorsExcluding}
 * (PriceObservationRepository) počítají anonymní observace za DEN, ne za řádek — od zavedení
 * dávkového zápisu (PriceObservationService.submit, víc cen z jedné cenovky jedním voláním) by
 * jinak jedno anonymní odeslání tří druhů ceny odemklo DRAFT zboží / PENDING obchod jedním
 * kliknutím (docs/reputace.md). {@code COALESCE(submitter_id, 'anon:' || core.day_utc(observed_
 * at))} je nativní SQL, který Mockito neověří — jediné místo v repu s reálnou Postgres DB je
 * ApplicationSmokeTest, tenhle test jde stejnou cestou (Testcontainers), jen na úrovni
 * repozitáře, ne celého GraphQL requestu.
 */
@Testcontainers
@SpringBootTest
class PriceObservationRepositoryIntegrationTest {

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
  private PriceObservationRepository priceObservationRepository;
  @Autowired
  private ProductRepository productRepository;
  @Autowired
  private StoreRepository storeRepository;
  @Autowired
  private CategoryRepository categoryRepository;

  private Product persistProduct(Category category) {
    return productRepository.saveAndFlush(Product.builder()
        .name("Test produkt " + UUID.randomUUID())
        .category(category)
        .unitBase(UnitBase.MASS)
        .netContentBase(java.math.BigDecimal.ONE)
        .status(ProductStatus.ACTIVE)
        .build());
  }

  private Store persistStore() {
    return storeRepository.saveAndFlush(Store.builder()
        .name("Test obchod " + UUID.randomUUID())
        .city("Praha")
        .country("CZ")
        .status(StoreStatus.ACTIVE)
        .build());
  }

  private void persistObservation(Product product, Store store, PriceKind kind, OffsetDateTime observedAt) {
    priceObservationRepository.saveAndFlush(PriceObservation.builder()
        .product(product)
        .store(store)
        .priceAmount(java.math.BigDecimal.TEN)
        .currency("CZK")
        .priceKind(kind)
        .quantityBasis(QuantityBasis.PACKAGE)
        .netContentBase(java.math.BigDecimal.ONE)
        .observedAt(observedAt)
        .submitter(null)
        .submitterKind(SubmitterKind.ANONYMOUS)
        .source(ObservationSource.WEB)
        .build());
  }

  @Test
  void anonymousObservationsFromSameDayCountAsOneContributor() {
    Category category = categoryRepository.saveAndFlush(
        Category.builder().name("Test kategorie " + UUID.randomUUID()).slug("test-" + UUID.randomUUID()).path("test").build());
    Product product = persistProduct(category);
    Store store = persistStore();

    // Tři anonymní ceny z jedné dávky (REGULAR + CLUB_CARD + MULTIBUY), stejný den — dřív by se
    // počítaly jako tři různí přispěvatelé, teď jako jeden.
    OffsetDateTime day = OffsetDateTime.parse("2026-08-18T10:00:00Z");
    persistObservation(product, store, PriceKind.REGULAR, day);
    persistObservation(product, store, PriceKind.CLUB_CARD, day.plusHours(1));
    persistObservation(product, store, PriceKind.MULTIBUY, day.plusHours(2));

    // excludingUserId simuluje zakladatele produktu (jiný, registrovaný účet) — "IS DISTINCT
    // FROM NULL" by s excludingUserId == null vyloučilo i anonymní řádky (NULL není DISTINCT
    // FROM NULL), takže tenhle parametr musí být reálná, odlišná hodnota, stejně jako
    // v produkčním volání promoteIfConfirmed(product.getCreatedByUserId()).
    long contributors = priceObservationRepository.countDistinctProductContributorsExcluding(product.getId(), -1L);

    assertThat(contributors).isEqualTo(1);
  }

  @Test
  void anonymousObservationsFromDifferentDaysCountSeparately() {
    Category category = categoryRepository.saveAndFlush(
        Category.builder().name("Test kategorie " + UUID.randomUUID()).slug("test-" + UUID.randomUUID()).path("test").build());
    Product product = persistProduct(category);
    Store store = persistStore();

    persistObservation(product, store, PriceKind.REGULAR, OffsetDateTime.parse("2026-08-16T10:00:00Z"));
    persistObservation(product, store, PriceKind.REGULAR, OffsetDateTime.parse("2026-08-17T10:00:00Z"));
    persistObservation(product, store, PriceKind.REGULAR, OffsetDateTime.parse("2026-08-18T10:00:00Z"));

    // excludingUserId simuluje zakladatele produktu (jiný, registrovaný účet) — "IS DISTINCT
    // FROM NULL" by s excludingUserId == null vyloučilo i anonymní řádky (NULL není DISTINCT
    // FROM NULL), takže tenhle parametr musí být reálná, odlišná hodnota, stejně jako
    // v produkčním volání promoteIfConfirmed(product.getCreatedByUserId()).
    long contributors = priceObservationRepository.countDistinctProductContributorsExcluding(product.getId(), -1L);

    assertThat(contributors).isEqualTo(3);
  }
}
