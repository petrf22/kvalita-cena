package cz.kvalitacena.service;

import cz.kvalitacena.config.ModerationProperties;
import cz.kvalitacena.controller.FlagResult;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.Media;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductReview;
import cz.kvalitacena.db.entity.RecordType;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.MediaRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductReviewRepository;
import cz.kvalitacena.db.repo.RecordFlagRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nahlášení záznamu — hlasuje se o FAKTU, nikdy o ČLOVĚKU (docs/reputace.md). Druhý hlas
 * téhož uživatele nic nemění (uq_record_flag_user), po dosažení prahu se záznam skryje.
 */
@ExtendWith(MockitoExtension.class)
class RecordFlagServiceTest {

  private static final Long USER_ID = 42L;
  private static final UUID PUBLIC_UID = UUID.randomUUID();
  private static final Long PRODUCT_ID = 7L;

  @Mock
  private RecordFlagRepository recordFlagRepository;
  @Mock
  private AppUserRepository appUserRepository;
  @Mock
  private ProductRepository productRepository;
  @Mock
  private StoreRepository storeRepository;
  @Mock
  private MediaRepository mediaRepository;
  @Mock
  private ProductReviewRepository productReviewRepository;

  private final ModerationProperties moderationProperties = new ModerationProperties();

  private RecordFlagService service() {
    moderationProperties.setFlagsToHide(3);
    moderationProperties.setPhotoFlagsToHide(1);
    moderationProperties.setReviewFlagsToHide(2);
    return new RecordFlagService(recordFlagRepository, appUserRepository, productRepository,
        storeRepository, mediaRepository, productReviewRepository, moderationProperties);
  }

  private void givenLoggedInUser() {
    when(appUserRepository.findByPublicUid(PUBLIC_UID)).thenReturn(Optional.of(AppUser.builder().id(USER_ID).build()));
  }

  @Test
  void anonymousCannotFlag() {
    assertThatThrownBy(() -> service().flag(RecordType.PRODUCT, PRODUCT_ID, "nesmysl", null))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void nonExistentRecordThrowsNotFound() {
    givenLoggedInUser();
    when(productRepository.existsById(PRODUCT_ID)).thenReturn(false);
    assertThatThrownBy(() -> service().flag(RecordType.PRODUCT, PRODUCT_ID, null, PUBLIC_UID))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void belowThresholdDoesNotHideRecord() {
    givenLoggedInUser();
    when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
    when(recordFlagRepository.countByRecordTypeAndRecordIdAndResolvedAtIsNull(RecordType.PRODUCT, PRODUCT_ID)).thenReturn(2L);

    FlagResult result = service().flag(RecordType.PRODUCT, PRODUCT_ID, "nesmysl", PUBLIC_UID);

    assertThat(result.flagCount()).isEqualTo(2);
    assertThat(result.hidden()).isFalse();
    verify(productRepository, never()).findById(any());
  }

  @Test
  void reachingThresholdHidesRecord() {
    givenLoggedInUser();
    when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
    when(recordFlagRepository.countByRecordTypeAndRecordIdAndResolvedAtIsNull(RecordType.PRODUCT, PRODUCT_ID)).thenReturn(3L);
    Product product = Product.builder().id(PRODUCT_ID).build();
    when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

    FlagResult result = service().flag(RecordType.PRODUCT, PRODUCT_ID, null, PUBLIC_UID);

    assertThat(result.hidden()).isTrue();
    ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
    verify(productRepository).save(captor.capture());
    assertThat(captor.getValue().getHiddenAt()).isNotNull();
  }

  @Test
  void singleFlagHidesPhotoButNotProduct() {
    // Fotka je nejrizikovější uživatelský obsah — jedno nahlášení stačí (docs/reputace.md),
    // na rozdíl od produktu/obchodu, kde jsou potřeba tři (viz reachingThresholdHidesRecord).
    Long mediaId = 99L;
    givenLoggedInUser();
    when(mediaRepository.existsById(mediaId)).thenReturn(true);
    when(recordFlagRepository.countByRecordTypeAndRecordIdAndResolvedAtIsNull(RecordType.PHOTO, mediaId)).thenReturn(1L);
    Media media = Media.builder().id(mediaId).build();
    when(mediaRepository.findById(mediaId)).thenReturn(Optional.of(media));

    FlagResult result = service().flag(RecordType.PHOTO, mediaId, "nevhodný obsah", PUBLIC_UID);

    assertThat(result.flagCount()).isEqualTo(1);
    assertThat(result.hidden()).isTrue();
    ArgumentCaptor<Media> captor = ArgumentCaptor.forClass(Media.class);
    verify(mediaRepository).save(captor.capture());
    assertThat(captor.getValue().getHiddenAt()).isNotNull();
  }

  @Test
  void twoFlagsHideReviewTextButThreeAreNeededForProduct() {
    // Práh recenze (2) je mezi fotkou (1) a zbožím/obchodem (3) — viz reachingThresholdHidesRecord.
    Long reviewId = 5L;
    givenLoggedInUser();
    when(productReviewRepository.existsById(reviewId)).thenReturn(true);
    when(recordFlagRepository.countByRecordTypeAndRecordIdAndResolvedAtIsNull(RecordType.REVIEW, reviewId)).thenReturn(2L);
    ProductReview review = ProductReview.builder().id(reviewId).build();
    when(productReviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

    FlagResult result = service().flag(RecordType.REVIEW, reviewId, "urážlivý text", PUBLIC_UID);

    assertThat(result.flagCount()).isEqualTo(2);
    assertThat(result.hidden()).isTrue();
    ArgumentCaptor<ProductReview> captor = ArgumentCaptor.forClass(ProductReview.class);
    verify(productReviewRepository).save(captor.capture());
    assertThat(captor.getValue().getHiddenAt()).isNotNull();
  }

  @Test
  void alreadyHiddenRecordIsNotSavedAgain() {
    givenLoggedInUser();
    when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
    when(recordFlagRepository.countByRecordTypeAndRecordIdAndResolvedAtIsNull(RecordType.PRODUCT, PRODUCT_ID)).thenReturn(3L);
    Product alreadyHidden = Product.builder().id(PRODUCT_ID).hiddenAt(java.time.OffsetDateTime.now()).build();
    when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(alreadyHidden));

    FlagResult result = service().flag(RecordType.PRODUCT, PRODUCT_ID, null, PUBLIC_UID);

    assertThat(result.hidden()).isTrue();
    verify(productRepository, never()).save(any());
  }
}
