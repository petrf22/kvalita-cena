package cz.kvalitacena.service;

import cz.kvalitacena.controller.ObservationPriceInput;
import cz.kvalitacena.controller.SubmitObservationsInput;
import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.AppException;
import cz.kvalitacena.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Souběžné zápisy ceny nad skutečným Postgresem — advisory zámky v
 * {@code PriceObservationService.submit} je musí seřadit dřív, než narazí na unikátní index.
 * Tam by transakce spadla a server by uměl vrátit jen {@code OBSERVATION_ALREADY_SUBMITTED_TODAY}
 * bez druhu ceny: mobil by nevěděl, jestli cenu zapsal, ani který druh z fronty vyřadit.
 *
 * <p>První zápis drží transakci otevřenou (latch), druhý se mezitím spustí; bez zámku by jeho
 * dohledání i pre-kontrola nic neviděly a zablokoval by se až na indexu.
 */
@Testcontainers
@SpringBootTest
class PriceObservationConcurrencyIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.docker.compose.enabled", () -> false);
  }

  @Autowired private PriceObservationService service;
  @Autowired private PriceObservationRepository priceObservationRepository;
  @Autowired private ProductRepository productRepository;
  @Autowired private StoreRepository storeRepository;
  @Autowired private CategoryRepository categoryRepository;
  @Autowired private AppUserRepository appUserRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void concurrentReplayOfSameSubmissionWritesOnceAndBothSucceed() throws Exception {
    // Formulář a fronta (nebo dvě opakování z WorkManageru) s tímtéž id, anonymně — anonym
    // jiný unikátní index nemá.
    Product product = persistProduct();
    Store store = persistStore();
    SubmitObservationsInput input = input(product, store, UUID.randomUUID(), PriceKind.REGULAR, PriceKind.CLUB_CARD);

    HeldTransaction first = holdOpen(() -> service.submit(input, null, ObservationSource.MOBILE));
    CompletableFuture<List<Long>> second = submitAsync(() -> service.submit(input, null, ObservationSource.MOBILE));
    assertWaitsForLock(second);

    first.commit();

    assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(first.ids());
    assertThat(priceObservationRepository.findByClientSubmissionIdOrderByIdAsc(input.clientSubmissionId()))
        .hasSize(2);
  }

  @Test
  void concurrentCollisionOfSameUserNamesThePriceKind() throws Exception {
    // Dvě RŮZNÁ odeslání téhož uživatele (ruční zápis a fronta) se srazí na REGULAR — druhé
    // musí dostat kolizi s druhem ceny, aby ho fronta uměla vyřadit a zbytek poslat.
    Product product = persistProduct();
    Store store = persistStore();
    AppUser user = persistUser();
    SubmitObservationsInput manual = input(product, store, UUID.randomUUID(), PriceKind.REGULAR);
    SubmitObservationsInput queued = input(product, store, UUID.randomUUID(), PriceKind.REGULAR, PriceKind.CLUB_CARD);

    HeldTransaction first = holdOpen(() -> service.submit(manual, user.getPublicUid(), ObservationSource.MOBILE));
    CompletableFuture<List<Long>> second =
        submitAsync(() -> service.submit(queued, user.getPublicUid(), ObservationSource.MOBILE));
    assertWaitsForLock(second);

    first.commit();

    assertThatThrownBy(() -> second.get(30, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isInstanceOfSatisfying(AppException.class, e -> {
          assertThat(e.getCode()).isEqualTo(ErrorCode.OBSERVATION_PRICE_KIND_ALREADY_SUBMITTED_TODAY);
          assertThat(e.getArgs()).containsExactly(PriceKind.REGULAR.name());
        });
  }

  private record HeldTransaction(List<Long> ids, CountDownLatch release, CompletableFuture<Void> done) {
    void commit() throws Exception {
      release.countDown();
      done.get(30, TimeUnit.SECONDS);
    }
  }

  /** Zapíše v transakci, kterou nechá otevřenou až do {@link HeldTransaction#commit()}. */
  private HeldTransaction holdOpen(java.util.function.Supplier<List<PriceObservation>> submit) throws Exception {
    CompletableFuture<List<Long>> written = new CompletableFuture<>();
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> done = CompletableFuture.runAsync(() ->
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
          written.complete(submit.get().stream().map(PriceObservation::getId).toList());
          try {
            if (!release.await(30, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Test nepustil první transakci");
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        }));
    return new HeldTransaction(written.get(30, TimeUnit.SECONDS), release, done);
  }

  private CompletableFuture<List<Long>> submitAsync(java.util.function.Supplier<List<PriceObservation>> submit) {
    return CompletableFuture.supplyAsync(() -> new TransactionTemplate(transactionManager)
        .execute(tx -> submit.get().stream().map(PriceObservation::getId).toList()));
  }

  private static void assertWaitsForLock(CompletableFuture<?> second) {
    assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
  }

  private static SubmitObservationsInput input(Product product, Store store, UUID clientSubmissionId, PriceKind... kinds) {
    List<ObservationPriceInput> prices = java.util.Arrays.stream(kinds)
        .map(kind -> new ObservationPriceInput(kind, new BigDecimal("29.90"), null, null, null, null))
        .toList();
    return new SubmitObservationsInput(product.getId(), store.getId(), null, null, null, null, prices, clientSubmissionId);
  }

  private Product persistProduct() {
    Category category = categoryRepository.saveAndFlush(Category.builder()
        .name("Test kategorie " + UUID.randomUUID()).slug("test-" + UUID.randomUUID()).path("test").build());
    return productRepository.saveAndFlush(Product.builder()
        .name("Test produkt " + UUID.randomUUID())
        .category(category)
        .unitBase(UnitBase.MASS)
        .netContentBase(BigDecimal.ONE)
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
}
