package cz.kvalitacena.service;

import cz.kvalitacena.config.MediaProperties;
import cz.kvalitacena.controller.Photo;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.Media;
import cz.kvalitacena.db.entity.RecordType;
import cz.kvalitacena.db.entity.UserProfile;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.MediaRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.db.repo.UserProfileRepository;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.TooManyRequestsException;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.CatalogRateLimiter;
import cz.kvalitacena.security.ViewerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nahrávání fotek jde VÝHRADNĚ na existující záznam (žádné osiřelé uploady), jen přihlášeným,
 * s denním limitem, limitem počtu na záznam a idempotencí podle sha256 — viz plán založení
 * fotek. Viditelnost skryté fotky drží stejné pravidlo jako u zboží/obchodu.
 */
@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

  private static final UUID PUBLIC_UID = UUID.randomUUID();
  private static final Long USER_ID = 42L;
  private static final Long OTHER_USER_ID = 99L;
  private static final Long PRODUCT_ID = 7L;

  @Mock
  private MediaRepository mediaRepository;
  @Mock
  private AppUserRepository appUserRepository;
  @Mock
  private ProductRepository productRepository;
  @Mock
  private StoreRepository storeRepository;
  @Mock
  private UserProfileRepository userProfileRepository;
  @Mock
  private MediaStorage mediaStorage;
  @Mock
  private ImageProcessingService imageProcessingService;
  @Mock
  private CatalogRateLimiter catalogRateLimiter;

  private MediaProperties mediaProperties;
  private MediaService service;

  @BeforeEach
  void setUp() {
    mediaProperties = new MediaProperties();
    mediaProperties.setMaxPhotosPerRecord(5);
    service = new MediaService(mediaRepository, appUserRepository, productRepository, storeRepository,
        userProfileRepository, mediaStorage, imageProcessingService, catalogRateLimiter, mediaProperties,
        TestMessages.instance());
  }

  private void givenLoggedInUser() {
    when(appUserRepository.findByPublicUid(PUBLIC_UID)).thenReturn(Optional.of(AppUser.builder().id(USER_ID).build()));
  }

  @Test
  void anonymousCannotUpload() {
    assertThatThrownBy(() -> service.upload(RecordType.PRODUCT, PRODUCT_ID, new byte[]{1}, null, null))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void uploadToNonexistentRecordThrowsNotFound() {
    givenLoggedInUser();
    when(productRepository.existsById(PRODUCT_ID)).thenReturn(false);

    assertThatThrownBy(() -> service.upload(RecordType.PRODUCT, PRODUCT_ID, new byte[]{1}, null, PUBLIC_UID))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void rateLimitExceededThrowsTooManyRequests() {
    givenLoggedInUser();
    when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
    when(catalogRateLimiter.tryAcquireMediaUpload(PUBLIC_UID)).thenReturn(false);

    assertThatThrownBy(() -> service.upload(RecordType.PRODUCT, PRODUCT_ID, new byte[]{1}, null, PUBLIC_UID))
        .isInstanceOf(TooManyRequestsException.class);
  }

  @Test
  void maxPhotosPerRecordIsEnforced() {
    givenLoggedInUser();
    when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
    when(catalogRateLimiter.tryAcquireMediaUpload(PUBLIC_UID)).thenReturn(true);
    when(mediaRepository.countByRecordTypeAndRecordId(RecordType.PRODUCT, PRODUCT_ID)).thenReturn(5L);

    assertThatThrownBy(() -> service.upload(RecordType.PRODUCT, PRODUCT_ID, new byte[]{1}, null, PUBLIC_UID))
        .isInstanceOf(ValidationException.class);
    verify(imageProcessingService, never()).process(any());
  }

  @Test
  void duplicateUploadReturnsExistingMediaInsteadOfCreatingNew() {
    givenLoggedInUser();
    when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
    when(catalogRateLimiter.tryAcquireMediaUpload(PUBLIC_UID)).thenReturn(true);
    when(mediaRepository.countByRecordTypeAndRecordId(RecordType.PRODUCT, PRODUCT_ID)).thenReturn(1L);

    byte[] sha = {1, 2, 3};
    ImageProcessingService.ProcessedImage processed =
        new ImageProcessingService.ProcessedImage(new byte[]{9}, new byte[]{8}, 100, 100, sha);
    when(imageProcessingService.process(any())).thenReturn(processed);
    Media existing = Media.builder().id(1L).sha256(sha).build();
    when(mediaRepository.findByRecordTypeAndRecordIdAndSha256(RecordType.PRODUCT, PRODUCT_ID, sha))
        .thenReturn(Optional.of(existing));

    Media result = service.upload(RecordType.PRODUCT, PRODUCT_ID, new byte[]{1}, null, PUBLIC_UID);

    assertThat(result).isSameAs(existing);
    verify(mediaStorage, never()).store(any(), any());
    verify(mediaRepository, never()).save(any());
  }

  @Test
  void successfulUploadStoresFileAndSavesRowWithNextSortOrder() {
    givenLoggedInUser();
    when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
    when(catalogRateLimiter.tryAcquireMediaUpload(PUBLIC_UID)).thenReturn(true);
    when(mediaRepository.countByRecordTypeAndRecordId(RecordType.PRODUCT, PRODUCT_ID)).thenReturn(2L);

    byte[] sha = {1, 2, 3};
    ImageProcessingService.ProcessedImage processed =
        new ImageProcessingService.ProcessedImage(new byte[]{9, 9}, new byte[]{8}, 100, 80, sha);
    when(imageProcessingService.process(any())).thenReturn(processed);
    when(mediaRepository.findByRecordTypeAndRecordIdAndSha256(RecordType.PRODUCT, PRODUCT_ID, sha))
        .thenReturn(Optional.empty());
    when(mediaStorage.store(processed.full(), processed.thumbnail())).thenReturn("2026/08/novy-klic.jpg");
    when(mediaRepository.save(any(Media.class))).thenAnswer(inv -> inv.getArgument(0));

    Media result = service.upload(RecordType.PRODUCT, PRODUCT_ID, new byte[]{1}, "  multipack  ", PUBLIC_UID);

    ArgumentCaptor<Media> captor = ArgumentCaptor.forClass(Media.class);
    verify(mediaRepository).save(captor.capture());
    Media saved = captor.getValue();
    assertThat(saved.getStorageKey()).isEqualTo("2026/08/novy-klic.jpg");
    assertThat(saved.getUploadedByUserId()).isEqualTo(USER_ID);
    assertThat(saved.getSortOrder()).isEqualTo(2);
    assertThat(saved.getCaption()).isEqualTo("multipack"); // ořezané mezery
    assertThat(saved.getWidth()).isEqualTo(100);
    assertThat(result).isEqualTo(saved);
  }

  @Test
  void onlyOwnerCanDeletePhoto() {
    givenLoggedInUser();
    Media media = Media.builder().id(1L).uploadedByUserId(OTHER_USER_ID).storageKey("k").build();
    when(mediaRepository.findById(1L)).thenReturn(Optional.of(media));

    assertThatThrownBy(() -> service.delete(1L, PUBLIC_UID)).isInstanceOf(UnauthorizedException.class);
    verify(mediaStorage, never()).delete(any());
  }

  @Test
  void ownerCanDeleteOwnPhoto() {
    givenLoggedInUser();
    Media media = Media.builder().id(1L).uploadedByUserId(USER_ID).storageKey("2026/08/k.jpg").build();
    when(mediaRepository.findById(1L)).thenReturn(Optional.of(media));

    service.delete(1L, PUBLIC_UID);

    verify(mediaStorage).delete("2026/08/k.jpg");
    verify(mediaRepository).delete(media);
  }

  @Test
  void hiddenPhotoIsVisibleOnlyToAuthor() {
    Media visibleToAll = Media.builder().id(1L).recordId(PRODUCT_ID).uploadedByUserId(OTHER_USER_ID).build();
    Media hiddenFromOthers = Media.builder().id(2L).recordId(PRODUCT_ID).uploadedByUserId(OTHER_USER_ID)
        .hiddenAt(OffsetDateTime.now()).build();
    when(mediaRepository.findByRecordTypeAndRecordIdOrderBySortOrderAscIdAsc(RecordType.PRODUCT, PRODUCT_ID))
        .thenReturn(List.of(visibleToAll, hiddenFromOthers));

    ViewerContext otherViewer = new ViewerContext(UUID.randomUUID(), 12345L, true);
    List<Photo> photos = service.photosFor(RecordType.PRODUCT, PRODUCT_ID, otherViewer);

    assertThat(photos).hasSize(1);
    assertThat(photos.get(0).id()).isEqualTo(1L);
  }

  @Test
  void authorSeesOwnHiddenPhoto() {
    Media hiddenFromOthers = Media.builder().id(2L).recordId(PRODUCT_ID).uploadedByUserId(USER_ID)
        .hiddenAt(OffsetDateTime.now()).build();
    when(mediaRepository.findByRecordTypeAndRecordIdOrderBySortOrderAscIdAsc(RecordType.PRODUCT, PRODUCT_ID))
        .thenReturn(List.of(hiddenFromOthers));

    ViewerContext author = new ViewerContext(PUBLIC_UID, USER_ID, true);
    List<Photo> photos = service.photosFor(RecordType.PRODUCT, PRODUCT_ID, author);

    assertThat(photos).hasSize(1);
    assertThat(photos.get(0).mine()).isTrue();
    assertThat(photos.get(0).hidden()).isTrue();
  }

  @Test
  void genericUploadRejectsUserRecordType() {
    // Kontrola recordType == USER je v upload() PŘED načtením uživatele — findByPublicUid se
    // tak vůbec nevolá, žádný stub navíc není potřeba.
    assertThatThrownBy(() -> service.upload(RecordType.USER, USER_ID, new byte[]{1}, null, PUBLIC_UID))
        .isInstanceOf(ValidationException.class);
    verify(imageProcessingService, never()).process(any());
  }

  @Test
  void anonymousCannotUploadAvatar() {
    assertThatThrownBy(() -> service.uploadAvatar(new byte[]{1}, null))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void uploadingSecondAvatarReplacesTheFirstOne() {
    AppUser user = AppUser.builder().id(USER_ID).publicUid(PUBLIC_UID).build();
    when(appUserRepository.findByPublicUid(PUBLIC_UID)).thenReturn(Optional.of(user));
    when(catalogRateLimiter.tryAcquireMediaUpload(PUBLIC_UID)).thenReturn(true);

    byte[] sha = {4, 5, 6};
    ImageProcessingService.ProcessedImage processed =
        new ImageProcessingService.ProcessedImage(new byte[]{9}, new byte[]{8}, 100, 100, sha);
    when(imageProcessingService.process(any())).thenReturn(processed);
    when(mediaRepository.findByRecordTypeAndRecordIdAndSha256(RecordType.USER, USER_ID, sha))
        .thenReturn(Optional.empty());
    when(mediaStorage.store(any(), any())).thenReturn("2026/08/avatar.jpg");
    Media savedMedia = Media.builder().id(77L).recordType(RecordType.USER).recordId(USER_ID)
        .storageKey("2026/08/avatar.jpg").uploadedByUserId(USER_ID).sha256(sha).build();
    when(mediaRepository.save(any(Media.class))).thenReturn(savedMedia);

    UserProfile existingProfile = UserProfile.builder().userId(USER_ID).avatarMediaId(11L).build();
    when(userProfileRepository.findById(USER_ID)).thenReturn(Optional.of(existingProfile));
    Media oldAvatar = Media.builder().id(11L).storageKey("2026/07/stary.jpg").build();
    when(mediaRepository.findById(11L)).thenReturn(Optional.of(oldAvatar));

    Photo result = service.uploadAvatar(new byte[]{1}, PUBLIC_UID);

    assertThat(result.id()).isEqualTo(77L);
    ArgumentCaptor<UserProfile> profileCaptor = ArgumentCaptor.forClass(UserProfile.class);
    verify(userProfileRepository).save(profileCaptor.capture());
    assertThat(profileCaptor.getValue().getAvatarMediaId()).isEqualTo(77L);
    // Starý avatar se smaže, ne jen přestane být odkazovaný.
    verify(mediaStorage).delete("2026/07/stary.jpg");
    verify(mediaRepository).delete(oldAvatar);
  }
}
