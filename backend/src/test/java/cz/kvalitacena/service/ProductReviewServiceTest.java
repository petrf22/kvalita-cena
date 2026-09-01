package cz.kvalitacena.service;

import cz.kvalitacena.controller.ProductQuality;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductReviewRepository;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hodnocení kvality je zatím jen hvězdičky 1–5 (5 nejlepší), bez textů a bez viditelnosti
 * (docs/datovy-model.md). Anonym nesmí hodnotit, jedno hodnocení na uživatele a produkt
 * (vynucuje DB unique index + native upsert — tady se ověřuje jen že service volá upsert
 * správně). Třída se dřív jmenovala {@code QualityRatingServiceTest}, přejmenováno spolu
 * s {@code core.product_quality_rating} → {@code core.product_review}.
 */
@ExtendWith(MockitoExtension.class)
class ProductReviewServiceTest {

  private static final Long PRODUCT_ID = 1L;
  private static final Long USER_ID = 42L;
  private static final UUID PUBLIC_UID = UUID.randomUUID();

  @Mock
  private ProductReviewRepository reviewRepository;
  @Mock
  private AppUserRepository appUserRepository;
  @Mock
  private ProductRepository productRepository;

  private ProductReviewService service() {
    return new ProductReviewService(reviewRepository, appUserRepository, productRepository);
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
}
