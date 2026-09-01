package cz.kvalitacena.service;

import cz.kvalitacena.config.ReviewProperties;
import cz.kvalitacena.controller.MyProductReview;
import cz.kvalitacena.controller.ProductQuality;
import cz.kvalitacena.controller.ProductReviewResult;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.ProductReview;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductReviewRepository;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.TooManyRequestsException;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.CatalogRateLimiter;
import cz.kvalitacena.security.ViewerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hvězdičky 1–5 (5 nejlepší) povinně, text recenze volitelně max
 * {@link ReviewProperties#getMaxTextLength()} znaků (docs/datovy-model.md). Anonym nesmí
 * hodnotit ani psát text; text bez existujícího hodnocení jde zamítnout
 * (REVIEW_REQUIRES_RATING) — u textu se to testuje zvlášť od hvězdiček, protože text vyžaduje
 * DŘÍVĚJŠÍ řádek, kdežto hvězdičky si řádek zakládají samy (upsert). Třída se dřív jmenovala
 * {@code QualityRatingServiceTest}, přejmenováno spolu s {@code core.product_quality_rating}
 * → {@code core.product_review}.
 */
@ExtendWith(MockitoExtension.class)
class ProductReviewServiceTest {

  private static final Long PRODUCT_ID = 1L;
  private static final Long USER_ID = 42L;
  private static final UUID PUBLIC_UID = UUID.randomUUID();
  private static final int MAX_TEXT_LENGTH = 1000;

  @Mock
  private ProductReviewRepository reviewRepository;
  @Mock
  private AppUserRepository appUserRepository;
  @Mock
  private ProductRepository productRepository;
  @Mock
  private PublicNameRenderer publicNameRenderer;
  @Mock
  private CatalogRateLimiter catalogRateLimiter;

  private ProductReviewService service() {
    ReviewProperties properties = new ReviewProperties();
    properties.setMaxTextLength(MAX_TEXT_LENGTH);
    properties.setMaxPerDay(20);
    return new ProductReviewService(reviewRepository, appUserRepository, productRepository,
        publicNameRenderer, properties, catalogRateLimiter);
  }

  @Test
  void anonymousViewerCannotRate() {
    assertThatThrownBy(() -> service().rate(PRODUCT_ID, 3, null))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void starsMustBeBetweenOneAndFive() {
    ProductReviewService service = service();
    assertThatThrownBy(() -> service.rate(PRODUCT_ID, 0, PUBLIC_UID))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> service.rate(PRODUCT_ID, 6, PUBLIC_UID))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void unknownProductIsRejected() {
    when(productRepository.existsById(PRODUCT_ID)).thenReturn(false);
    assertThatThrownBy(() -> service().rate(PRODUCT_ID, 3, PUBLIC_UID))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void secondCallOverwritesInsteadOfCreatingSecondRow() {
    when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
    when(appUserRepository.findByPublicUid(PUBLIC_UID))
        .thenReturn(Optional.of(AppUser.builder().id(USER_ID).build()));
    when(reviewRepository.summarize(any())).thenReturn(List.of());

    ProductReviewService service = service();
    service.rate(PRODUCT_ID, 2, PUBLIC_UID);
    service.rate(PRODUCT_ID, 4, PUBLIC_UID);

    // Upsert (ON CONFLICT DO UPDATE) je jediná cesta zápisu — žádné save/insert napřímo,
    // vynucení unikátnosti (product_id, user_id) je na DB indexu, ne v Javě.
    verify(reviewRepository, org.mockito.Mockito.times(2)).upsert(eq(PRODUCT_ID), eq(USER_ID), anyShort());
  }

  @Test
  void averageOfOneAndFiveIsThree() {
    ProductReviewRepository.QualityRow row = new ProductReviewRepository.QualityRow() {
      @Override
      public Long getProductId() {
        return PRODUCT_ID;
      }

      @Override
      public Double getAverage() {
        return 3.0;
      }

      @Override
      public long getCount() {
        return 2;
      }
    };
    when(reviewRepository.summarize(List.of(PRODUCT_ID))).thenReturn(List.of(row));

    var summaries = service().summariesFor(List.of(PRODUCT_ID));

    ProductQuality quality = summaries.get(PRODUCT_ID);
    assertThat(quality.average()).isEqualByComparingTo("3.00");
    assertThat(quality.count()).isEqualTo(2);
  }

  @Test
  void unratedProductHasNullAverage() {
    var summaries = service().summariesFor(List.of(PRODUCT_ID));
    assertThat(summaries).isEmpty();
  }

  // --- text recenze ---

  @Test
  void anonymousViewerCannotSaveText() {
    assertThatThrownBy(() -> service().saveText(PRODUCT_ID, "Dobré", null))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void blankTextIsRejected() {
    assertThatThrownBy(() -> service().saveText(PRODUCT_ID, "   ", PUBLIC_UID))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void tooLongTextIsRejected() {
    String tooLong = "a".repeat(MAX_TEXT_LENGTH + 1);
    assertThatThrownBy(() -> service().saveText(PRODUCT_ID, tooLong, PUBLIC_UID))
        .isInstanceOf(ValidationException.class);
  }

  /** Text bez existujícího hodnocení nejde založit — hvězdičky se zadávají přes rateProduct. */
  @Test
  void textWithoutExistingRatingIsRejected() {
    when(appUserRepository.findByPublicUid(PUBLIC_UID))
        .thenReturn(Optional.of(AppUser.builder().id(USER_ID).build()));
    when(reviewRepository.findByProductIdAndUserId(PRODUCT_ID, USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().saveText(PRODUCT_ID, "Dobré mléko", PUBLIC_UID))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void rateLimitExceededRejectsText() {
    when(appUserRepository.findByPublicUid(PUBLIC_UID))
        .thenReturn(Optional.of(AppUser.builder().id(USER_ID).build()));
    when(reviewRepository.findByProductIdAndUserId(PRODUCT_ID, USER_ID))
        .thenReturn(Optional.of(ProductReview.builder().productId(PRODUCT_ID).userId(USER_ID).stars((short) 4).build()));
    when(catalogRateLimiter.tryAcquireReviewText(PUBLIC_UID)).thenReturn(false);

    assertThatThrownBy(() -> service().saveText(PRODUCT_ID, "Dobré mléko", PUBLIC_UID))
        .isInstanceOf(TooManyRequestsException.class);
  }

  @Test
  void savingTextTrimsAndKeepsStarsUntouched() {
    ProductReview existing = ProductReview.builder().productId(PRODUCT_ID).userId(USER_ID).stars((short) 4).build();
    when(appUserRepository.findByPublicUid(PUBLIC_UID))
        .thenReturn(Optional.of(AppUser.builder().id(USER_ID).build()));
    when(reviewRepository.findByProductIdAndUserId(PRODUCT_ID, USER_ID)).thenReturn(Optional.of(existing));
    when(catalogRateLimiter.tryAcquireReviewText(PUBLIC_UID)).thenReturn(true);

    MyProductReview result = service().saveText(PRODUCT_ID, "  Dobré mléko  ", PUBLIC_UID);

    assertThat(result.stars()).isEqualTo(4);
    assertThat(result.text()).isEqualTo("Dobré mléko");
    verify(reviewRepository).save(existing);
  }

  @Test
  void deletingTextKeepsStars() {
    ProductReview existing = ProductReview.builder().productId(PRODUCT_ID).userId(USER_ID).stars((short) 5)
        .text("Staré hodnocení").textUpdatedAt(OffsetDateTime.now()).build();
    when(appUserRepository.findByPublicUid(PUBLIC_UID))
        .thenReturn(Optional.of(AppUser.builder().id(USER_ID).build()));
    when(reviewRepository.findByProductIdAndUserId(PRODUCT_ID, USER_ID)).thenReturn(Optional.of(existing));

    MyProductReview result = service().deleteText(PRODUCT_ID, PUBLIC_UID);

    assertThat(result.stars()).isEqualTo(5);
    assertThat(result.text()).isNull();
    assertThat(existing.getText()).isNull();
    assertThat(existing.getTextUpdatedAt()).isNull();
  }

  // --- výpis recenzí pod zbožím (T1 gating) ---

  @Test
  void anonymousViewerGetsLoginRequiredWithRealTotalCount() {
    when(reviewRepository.countVisibleTextsByProduct(PRODUCT_ID)).thenReturn(3L);

    ProductReviewResult result = service().reviewsFor(PRODUCT_ID, null, null, ViewerContext.ANONYMOUS);

    assertThat(result.loginRequired()).isTrue();
    assertThat(result.items()).isEmpty();
    assertThat(result.totalCount()).isEqualTo(3);
  }

  @Test
  void loggedInViewerSeesOwnReviewMarkedAsMine() {
    ViewerContext viewer = new ViewerContext(PUBLIC_UID, USER_ID, false, false);
    ProductReview review = ProductReview.builder().id(10L).productId(PRODUCT_ID).userId(USER_ID)
        .stars((short) 5).text("Skvělé").createdAt(OffsetDateTime.now()).build();
    AppUser author = AppUser.builder().id(USER_ID).publicUid(PUBLIC_UID).build();
    when(reviewRepository.countVisibleTextsByProduct(PRODUCT_ID)).thenReturn(1L);
    when(reviewRepository.findVisibleTextsByProduct(PRODUCT_ID, 20, 0)).thenReturn(List.of(review));
    when(appUserRepository.findAllById(List.of(USER_ID))).thenReturn(List.of(author));
    lenient().when(publicNameRenderer.render(author, viewer)).thenReturn("Petr #4271");

    ProductReviewResult result = service().reviewsFor(PRODUCT_ID, null, null, viewer);

    assertThat(result.loginRequired()).isFalse();
    assertThat(result.items()).hasSize(1);
    assertThat(result.items().get(0).mine()).isTrue();
    assertThat(result.items().get(0).authorName()).isEqualTo("Petr #4271");
  }
}
