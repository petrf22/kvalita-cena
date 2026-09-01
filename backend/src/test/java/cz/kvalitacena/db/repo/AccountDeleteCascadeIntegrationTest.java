package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.AppUserStatus;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductReview;
import cz.kvalitacena.db.entity.ProductStatus;
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
 * Ověřuje záruku z docs/soukromi.md ("Výjimka: hodnocení kvality zboží vazbu
 * nepseudonymizuje" — {@code ON DELETE CASCADE}, ne {@code SET NULL}) na SKUTEČNÉ Postgres FK,
 * ne jen jako tvrzení v dokumentu. Mockito by tohle neodhalilo — {@link
 * cz.kvalitacena.service.AccountService#confirmDelete} jen zavolá {@code
 * appUserRepository.delete(...)}, kaskádu dělá databáze sama
 * (fk_product_review_user, 2026-08-05/01-product-quality-rating.yaml).
 */
@Testcontainers
@SpringBootTest
class AccountDeleteCascadeIntegrationTest {

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
  private AppUserRepository appUserRepository;
  @Autowired
  private ProductRepository productRepository;
  @Autowired
  private CategoryRepository categoryRepository;
  @Autowired
  private ProductReviewRepository productReviewRepository;

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

  @Test
  void deletingAccountCascadesReviewIncludingText() {
    AppUser user = persistUser();
    Product product = persistProduct();
    ProductReview review = productReviewRepository.saveAndFlush(ProductReview.builder()
        .productId(product.getId())
        .userId(user.getId())
        .stars((short) 5)
        .text("Výborné mléko")
        .build());
    Long reviewId = review.getId();

    appUserRepository.delete(user);
    appUserRepository.flush();

    assertThat(productReviewRepository.findById(reviewId)).isEmpty();
  }

  /** Hvězdičky beze textu podléhají stejné kaskádě — jeden mechanismus, ne dva. */
  @Test
  void deletingAccountCascadesReviewWithoutText() {
    AppUser user = persistUser();
    Product product = persistProduct();
    ProductReview review = productReviewRepository.saveAndFlush(ProductReview.builder()
        .productId(product.getId())
        .userId(user.getId())
        .stars((short) 3)
        .build());
    Long reviewId = review.getId();

    appUserRepository.delete(user);
    appUserRepository.flush();

    assertThat(productReviewRepository.findById(reviewId)).isEmpty();
  }
}
