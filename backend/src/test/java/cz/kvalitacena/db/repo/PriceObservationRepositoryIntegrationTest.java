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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code countDistinctProductContributorsExcluding}/{@code countDistinctContributorsExcluding}
 * (a jejich {@code …Batch} varianty, PriceObservationRepository) počítají jen registrované
 * přispěvatele — anonymní observace (submitter_id NULL, docs/soukromi.md) se do prahu potvrzení
 * (docs/reputace.md, "Zboží bez čárového kódu") nepočítají vůbec, ať už jich je jeden den kolik
 * chce. Anonymní identity se nedají mezi sebou rozlišit ani přiřadit k účtu, takže by šlo jednu
 * anonymní osobu vydávat za libovolný počet různých přispěvatelů. Nativní SQL Mockito neověří —
 * jediné další místo v repu s reálnou Postgres DB je ApplicationSmokeTest, tenhle test jde
 * stejnou cestou (Testcontainers), jen na úrovni repozitáře, ne celého GraphQL requestu.
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
  @Autowired
  private AppUserRepository appUserRepository;

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

  private AppUser persistUser() {
    String unique = UUID.randomUUID().toString();
    return appUserRepository.saveAndFlush(AppUser.builder()
        .emailHash(unique.getBytes())
        .emailEnc(unique.getBytes())
        .publicHandle(unique.substring(0, 30)) // auth.app_user.public_handle je VARCHAR(40)
        .handleAdjective("blue")
        .handleNoun("stork")
        .handleNumber((short) 1)
        .status(AppUserStatus.ACTIVE)
        .build());
  }

  private void persistObservation(Product product, Store store, AppUser submitter, PriceKind kind, OffsetDateTime observedAt) {
    priceObservationRepository.saveAndFlush(PriceObservation.builder()
        .product(product)
        .store(store)
        .priceAmount(java.math.BigDecimal.TEN)
        .currency("CZK")
        .priceKind(kind)
        .quantityBasis(QuantityBasis.PACKAGE)
        .netContentBase(java.math.BigDecimal.ONE)
        .observedAt(observedAt)
        .submitter(submitter)
        .submitterKind(submitter == null ? SubmitterKind.ANONYMOUS : SubmitterKind.REGISTERED)
        .source(ObservationSource.WEB)
        .build());
  }

  @Test
  void anonymousObservationsAreNotCountedAsContributors() {
    Category category = categoryRepository.saveAndFlush(
        Category.builder().name("Test kategorie " + UUID.randomUUID()).slug("test-" + UUID.randomUUID()).path("test").build());
    Product product = persistProduct(category);
    Store store = persistStore();

    // Tři anonymní zápisy, různé dny — žádný z nich nemá vazbu na účet, takže žádný nesmí
    // počítat do prahu potvrzení, ani jednotlivě.
    persistObservation(product, store, null, PriceKind.REGULAR, OffsetDateTime.parse("2026-08-16T10:00:00Z"));
    persistObservation(product, store, null, PriceKind.REGULAR, OffsetDateTime.parse("2026-08-17T10:00:00Z"));
    persistObservation(product, store, null, PriceKind.REGULAR, OffsetDateTime.parse("2026-08-18T10:00:00Z"));

    long contributors = priceObservationRepository.countDistinctProductContributorsExcluding(product.getId(), -1L);

    assertThat(contributors).isZero();
  }

  @Test
  void registeredContributorsAreCountedOnce() {
    Category category = categoryRepository.saveAndFlush(
        Category.builder().name("Test kategorie " + UUID.randomUUID()).slug("test-" + UUID.randomUUID()).path("test").build());
    Product product = persistProduct(category);
    Store store = persistStore();
    AppUser first = persistUser();
    AppUser second = persistUser();

    // První uživatel zapíše dvě ceny (jedna dávka i tak počítá jako jeden přispěvatel), druhý
    // jednu — anonymní zápis mezi tím se nepočítá vůbec.
    OffsetDateTime day = OffsetDateTime.parse("2026-08-18T10:00:00Z");
    persistObservation(product, store, first, PriceKind.REGULAR, day);
    persistObservation(product, store, first, PriceKind.CLUB_CARD, day.plusHours(1));
    persistObservation(product, store, null, PriceKind.MULTIBUY, day.plusHours(2));
    persistObservation(product, store, second, PriceKind.REGULAR, day.plusDays(1));

    long contributors = priceObservationRepository.countDistinctProductContributorsExcluding(product.getId(), -1L);

    assertThat(contributors).isEqualTo(2);
  }

  @Test
  void authorsOwnObservationsAreExcluded() {
    Category category = categoryRepository.saveAndFlush(
        Category.builder().name("Test kategorie " + UUID.randomUUID()).slug("test-" + UUID.randomUUID()).path("test").build());
    Product product = persistProduct(category);
    Store store = persistStore();
    AppUser author = persistUser();
    AppUser other = persistUser();

    persistObservation(product, store, author, PriceKind.REGULAR, OffsetDateTime.parse("2026-08-18T10:00:00Z"));
    persistObservation(product, store, other, PriceKind.REGULAR, OffsetDateTime.parse("2026-08-19T10:00:00Z"));

    // excludingUserId simuluje zakladatele produktu — "IS DISTINCT FROM NULL" by s
    // excludingUserId == null vyloučilo i anonymní řádky (NULL není DISTINCT FROM NULL), proto
    // musí jít o reálnou hodnotu, stejně jako v produkčním promoteIfConfirmed(createdByUserId).
    long contributors = priceObservationRepository.countDistinctProductContributorsExcluding(product.getId(), author.getId());

    assertThat(contributors).isEqualTo(1);
  }

  @Test
  void batchVariantsAlsoExcludeAnonymousAndAuthor() {
    Category category = categoryRepository.saveAndFlush(
        Category.builder().name("Test kategorie " + UUID.randomUUID()).slug("test-" + UUID.randomUUID()).path("test").build());
    AppUser author = persistUser();
    AppUser other = persistUser();

    Product product = productRepository.saveAndFlush(Product.builder()
        .name("Test produkt " + UUID.randomUUID())
        .category(category)
        .unitBase(UnitBase.MASS)
        .netContentBase(java.math.BigDecimal.ONE)
        .status(ProductStatus.DRAFT)
        .generic(true)
        .createdByUserId(author.getId())
        .build());
    Store store = storeRepository.saveAndFlush(Store.builder()
        .name("Test obchod " + UUID.randomUUID())
        .city("Praha")
        .country("CZ")
        .status(StoreStatus.PENDING)
        .createdByUserId(author.getId())
        .build());

    persistObservation(product, store, author, PriceKind.REGULAR, OffsetDateTime.parse("2026-08-18T10:00:00Z"));
    persistObservation(product, store, null, PriceKind.REGULAR, OffsetDateTime.parse("2026-08-18T11:00:00Z"));
    persistObservation(product, store, other, PriceKind.REGULAR, OffsetDateTime.parse("2026-08-19T10:00:00Z"));

    List<PriceObservationRepository.ContributorCount> productCounts =
        priceObservationRepository.countDistinctProductContributorsExcludingBatch(List.of(product.getId()));
    List<PriceObservationRepository.ContributorCount> storeCounts =
        priceObservationRepository.countDistinctContributorsExcludingBatch(List.of(store.getId()));

    // Autor je vyloučený (JOIN na created_by_user_id), anonym se nepočítá vůbec — zbývá "other".
    assertThat(productCounts).singleElement().satisfies(c -> {
      assertThat(c.getId()).isEqualTo(product.getId());
      assertThat(c.getCnt()).isEqualTo(1);
    });
    assertThat(storeCounts).singleElement().satisfies(c -> {
      assertThat(c.getId()).isEqualTo(store.getId());
      assertThat(c.getCnt()).isEqualTo(1);
    });
  }
}
