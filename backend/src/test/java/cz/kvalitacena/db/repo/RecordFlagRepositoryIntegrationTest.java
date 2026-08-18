package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.AppUserStatus;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.entity.RecordFlag;
import cz.kvalitacena.db.entity.RecordType;
import cz.kvalitacena.db.entity.UnitBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nativní agregační dotazy (GROUP BY, string_agg s oddělovačem, podmíněný filtr přes bind
 * parametr) Mockito neověří — jediné místo v repu s reálnou Postgres DB je ApplicationSmokeTest,
 * tenhle test jde stejnou cestou (Testcontainers, viz PriceObservationRepositoryIntegrationTest)
 * jen na úrovni repozitáře. Klíčová regrese: po vyřízení nahlášení (resolveAllPending) se stará
 * nahlášení nesmí počítat znovu do prahu (RecordFlagService, docs/reputace.md, "Moderace").
 *
 * <p>Testy běží nad JEDNÍM sdíleným Postgresem bez rollbacku mezi metodami (Testcontainers
 * kontejner je statický) — proto se nikdy neasertuje absolutní velikost celé fronty, jen
 * přítomnost/nepřítomnost KONKRÉTNÍHO záznamu z daného testu ({@link #findGroupFor}) nebo
 * delta u agregátních počtů.
 */
@Testcontainers
@SpringBootTest
class RecordFlagRepositoryIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.docker.compose.enabled", () -> false);
  }

  private static final int LARGE_PAGE = 500;

  @Autowired
  private RecordFlagRepository recordFlagRepository;
  @Autowired
  private ProductRepository productRepository;
  @Autowired
  private CategoryRepository categoryRepository;
  @Autowired
  private AppUserRepository appUserRepository;

  private Product persistProduct() {
    Category category = categoryRepository.saveAndFlush(
        Category.builder().name("Test kategorie " + UUID.randomUUID()).slug("test-" + UUID.randomUUID()).path("test").build());
    return productRepository.saveAndFlush(Product.builder()
        .name("Test produkt " + UUID.randomUUID())
        .category(category)
        .unitBase(UnitBase.MASS)
        .netContentBase(BigDecimal.ONE)
        .status(ProductStatus.ACTIVE)
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

  private void flag(Product product, AppUser user, String reason) {
    recordFlagRepository.save(RecordFlag.builder()
        .recordType(RecordType.PRODUCT)
        .recordId(product.getId())
        .userId(user.getId())
        .reason(reason)
        .build());
  }

  private RecordFlagRepository.FlaggedGroup findGroupFor(String recordType, Long recordId) {
    return recordFlagRepository.findUnresolvedGroups(recordType, LARGE_PAGE, 0).stream()
        .filter(g -> g.getRecordId().equals(recordId))
        .findFirst()
        .orElse(null);
  }

  @Test
  void findUnresolvedGroupsExcludesResolvedFlagsAndJoinsReasons() {
    Product product = persistProduct();
    long before = recordFlagRepository.countUnresolvedGroups(null);
    flag(product, persistUser(), "cena neexistuje");
    flag(product, persistUser(), "duplicitni zbozi");
    recordFlagRepository.flush();

    RecordFlagRepository.FlaggedGroup group = findGroupFor(null, product.getId());

    assertThat(group).isNotNull();
    assertThat(group.getRecordType()).isEqualTo("PRODUCT");
    assertThat(group.getFlagCount()).isEqualTo(2);
    assertThat(group.getReasons()).contains("cena neexistuje").contains("duplicitni zbozi");
    assertThat(recordFlagRepository.countUnresolvedGroups(null)).isEqualTo(before + 1);
  }

  @Test
  void resolvedFlagDoesNotAppearInQueueOrThreshold() {
    Product product = persistProduct();
    AppUser reporter = persistUser();
    AppUser moderator = persistUser();
    flag(product, reporter, "nesmysl");
    recordFlagRepository.flush();

    assertThat(recordFlagRepository.countByRecordTypeAndRecordIdAndResolvedAtIsNull(RecordType.PRODUCT, product.getId()))
        .isEqualTo(1);
    assertThat(findGroupFor(null, product.getId())).isNotNull();

    int updated = recordFlagRepository.resolveAllPending("PRODUCT", product.getId(), moderator.getId(), "DISMISSED");
    recordFlagRepository.flush();

    assertThat(updated).isEqualTo(1);
    assertThat(findGroupFor(null, product.getId())).isNull();
    assertThat(recordFlagRepository.countByRecordTypeAndRecordIdAndResolvedAtIsNull(RecordType.PRODUCT, product.getId()))
        .isEqualTo(0);
  }

  @Test
  void resolveAllPendingIsIdempotentAndDoesNotResurrectOldFlagsAfterNewOne() {
    // Klíčová regrese: tři stará nahlášení se vyřídí, čtvrté nové ať se počítá samo (1),
    // ne jako 4 — jinak by jediný nový hlas skryl odkrytý záznam okamžitě zpátky
    // (RecordFlagService.flag počítá countByRecordTypeAndRecordIdAndResolvedAtIsNull).
    Product product = persistProduct();
    AppUser moderator = persistUser();
    flag(product, persistUser(), "a");
    flag(product, persistUser(), "b");
    flag(product, persistUser(), "c");
    recordFlagRepository.flush();

    int firstResolve = recordFlagRepository.resolveAllPending("PRODUCT", product.getId(), moderator.getId(), "DISMISSED");
    recordFlagRepository.flush();
    assertThat(firstResolve).isEqualTo(3);

    // Druhé volání nad stejným záznamem beze změny je no-op (idempotence).
    int secondResolve = recordFlagRepository.resolveAllPending("PRODUCT", product.getId(), moderator.getId(), "DISMISSED");
    assertThat(secondResolve).isEqualTo(0);

    flag(product, persistUser(), "novy nesmysl");
    recordFlagRepository.flush();

    assertThat(recordFlagRepository.countByRecordTypeAndRecordIdAndResolvedAtIsNull(RecordType.PRODUCT, product.getId()))
        .isEqualTo(1);
  }

  @Test
  void recordTypeFilterLimitsQueue() {
    Product product = persistProduct();
    flag(product, persistUser(), "nesmysl");
    recordFlagRepository.flush();

    assertThat(findGroupFor("STORE", product.getId())).isNull();
    assertThat(findGroupFor("PRODUCT", product.getId())).isNotNull();
  }
}
