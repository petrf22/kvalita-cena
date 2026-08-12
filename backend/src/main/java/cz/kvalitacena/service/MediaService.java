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
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.TooManyRequestsException;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.CatalogRateLimiter;
import cz.kvalitacena.security.ViewerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Fotky zboží a provozoven — nahrávají se VÝHRADNĚ na existující záznam (žádné osiřelé
 * uploady, viz plán založení fotek), binární obsah jde přes {@link MediaStorage},
 * zpracování (přeuložení z pixelů, EXIF pryč) přes {@link ImageProcessingService}.
 *
 * <p>Viditelnost skryté fotky řídí stejný predikát jako u produktu/obchodu
 * (docs/reputace.md): {@code hidden_at IS NULL OR uploaded_by_user_id = viewer.userId} — autor
 * musí vědět, co se s jeho příspěvkem děje.
 */
@Service
@RequiredArgsConstructor
public class MediaService {

  private final MediaRepository mediaRepository;
  private final AppUserRepository appUserRepository;
  private final ProductRepository productRepository;
  private final StoreRepository storeRepository;
  private final UserProfileRepository userProfileRepository;
  private final MediaStorage mediaStorage;
  private final ImageProcessingService imageProcessingService;
  private final CatalogRateLimiter catalogRateLimiter;
  private final MediaProperties mediaProperties;
  private final Messages messages;

  @Transactional
  public Media upload(RecordType recordType, Long recordId, byte[] raw, String caption, UUID viewerPublicUid) {
    if (viewerPublicUid == null) {
      throw new UnauthorizedException(ErrorCode.PHOTO_UPLOAD_REQUIRES_LOGIN);
    }
    if (recordType == RecordType.PHOTO) {
      throw new ValidationException(ErrorCode.PHOTO_CANNOT_ATTACH_TO_PHOTO);
    }
    // Avatar jde jen přes uploadAvatar() (recordId se bere z Authentication, ne z requestu) —
    // jinak by šlo tímhle obecným endpointem nahrát fotku pod libovolné cizí user_id.
    if (recordType == RecordType.USER) {
      throw new ValidationException(ErrorCode.PHOTO_CANNOT_ATTACH_TO_USER);
    }
    AppUser user = appUserRepository.findByPublicUid(viewerPublicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.ACCOUNT_GONE));
    requireRecordExists(recordType, recordId);
    if (!catalogRateLimiter.tryAcquireMediaUpload(viewerPublicUid)) {
      throw new TooManyRequestsException();
    }

    long existingCount = mediaRepository.countByRecordTypeAndRecordId(recordType, recordId);
    if (existingCount >= mediaProperties.getMaxPhotosPerRecord()) {
      throw new ValidationException(ErrorCode.PHOTO_LIMIT_REACHED, mediaProperties.getMaxPhotosPerRecord());
    }

    ImageProcessingService.ProcessedImage processed = imageProcessingService.process(raw);

    // Idempotence — druhé nahrání téhož obsahu ke stejnému záznamu (např. zopakovaný request
    // z mobilu) vrátí existující řádek místo duplikátu.
    Optional<Media> existing =
        mediaRepository.findByRecordTypeAndRecordIdAndSha256(recordType, recordId, processed.sha256());
    if (existing.isPresent()) {
      return existing.get();
    }

    String storageKey = mediaStorage.store(processed.full(), processed.thumbnail());
    Media media = Media.builder()
        .recordType(recordType)
        .recordId(recordId)
        .storageKey(storageKey)
        .uploadedByUserId(user.getId())
        .contentType("image/jpeg")
        .width(processed.width())
        .height(processed.height())
        .byteSize(processed.full().length)
        .sha256(processed.sha256())
        .caption(blankToNull(caption))
        .sortOrder((int) existingCount)
        .build();
    return mediaRepository.save(media);
  }

  @Transactional
  public void delete(Long mediaId, UUID viewerPublicUid) {
    Media media = requireOwnMedia(mediaId, viewerPublicUid, ErrorCode.PHOTO_DELETE_NOT_OWNER);
    mediaStorage.delete(media.getStorageKey());
    mediaRepository.delete(media);
  }

  @Transactional
  public Media update(Long mediaId, String caption, Integer sortOrder, UUID viewerPublicUid) {
    Media media = requireOwnMedia(mediaId, viewerPublicUid, ErrorCode.PHOTO_UPDATE_NOT_OWNER);
    if (caption != null) {
      media.setCaption(blankToNull(caption));
    }
    if (sortOrder != null) {
      media.setSortOrder(sortOrder);
    }
    return mediaRepository.save(media);
  }

  @Transactional(readOnly = true)
  public List<Photo> photosFor(RecordType recordType, Long recordId, ViewerContext viewer) {
    return mediaRepository.findByRecordTypeAndRecordIdOrderBySortOrderAscIdAsc(recordType, recordId).stream()
        .filter(media -> visibleTo(media, viewer))
        .map(media -> toPhoto(media, viewer))
        .toList();
  }

  /** Dotažení fotek pro víc záznamů najednou (GraphQL {@code @BatchMapping}) — žádné N+1. */
  @Transactional(readOnly = true)
  public Map<Long, List<Photo>> photosForBatch(RecordType recordType, Collection<Long> recordIds, ViewerContext viewer) {
    return mediaRepository.findByRecordTypeAndRecordIdInOrderBySortOrderAscIdAsc(recordType, recordIds).stream()
        .filter(media -> visibleTo(media, viewer))
        .collect(Collectors.groupingBy(Media::getRecordId,
            Collectors.mapping(media -> toPhoto(media, viewer), Collectors.toList())));
  }

  public Photo toPhoto(Media media, ViewerContext viewer) {
    boolean mine = viewer.userId() != null && media.getUploadedByUserId().equals(viewer.userId());
    return new Photo(media.getId(), "/api/media/" + media.getId(), "/api/media/" + media.getId() + "/thumb",
        media.getWidth(), media.getHeight(), media.getCaption(), mine, media.isHidden(),
        messages.get("attribution.media"));
  }

  private Media requireOwnMedia(Long mediaId, UUID viewerPublicUid, ErrorCode notOwnerCode) {
    if (viewerPublicUid == null) {
      throw new UnauthorizedException(ErrorCode.PHOTO_ACTION_REQUIRES_LOGIN);
    }
    AppUser user = appUserRepository.findByPublicUid(viewerPublicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.ACCOUNT_GONE));
    Media media = mediaRepository.findById(mediaId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.PHOTO_NOT_FOUND));
    if (!media.getUploadedByUserId().equals(user.getId())) {
      // Rozpad na dva kódy (mazání/úprava), NE interpolace "Fotku smí " + action + " jen autor"
      // — v pl/sk se mění slovosled i vazba slovesa (viz AppException).
      throw new UnauthorizedException(notOwnerCode);
    }
    return media;
  }

  private boolean visibleTo(Media media, ViewerContext viewer) {
    return !media.isHidden() || media.getUploadedByUserId().equals(viewer.userId());
  }

  /**
   * Avatar profilu — na rozdíl od {@link #upload} je vždy nejvýš jeden na uživatele
   * (nahrazení, ne přidání do fronty) a {@code recordId} se bere VÝHRADNĚ z {@code
   * viewerPublicUid}, nikdy z requestu (docs/soukromi.md — v API nikdy DB id). Idempotence
   * (stejný obsah nahraný podruhé) funguje stejně jako u {@link #upload}.
   */
  @Transactional
  public Photo uploadAvatar(byte[] raw, UUID viewerPublicUid) {
    if (viewerPublicUid == null) {
      throw new UnauthorizedException(ErrorCode.AVATAR_REQUIRES_LOGIN);
    }
    AppUser user = appUserRepository.findByPublicUid(viewerPublicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.ACCOUNT_GONE));
    if (!catalogRateLimiter.tryAcquireMediaUpload(viewerPublicUid)) {
      throw new TooManyRequestsException();
    }

    ImageProcessingService.ProcessedImage processed = imageProcessingService.process(raw);

    Media media = mediaRepository
        .findByRecordTypeAndRecordIdAndSha256(RecordType.USER, user.getId(), processed.sha256())
        .orElseGet(() -> {
          String storageKey = mediaStorage.store(processed.full(), processed.thumbnail());
          return mediaRepository.save(Media.builder()
              .recordType(RecordType.USER)
              .recordId(user.getId())
              .storageKey(storageKey)
              .uploadedByUserId(user.getId())
              .contentType("image/jpeg")
              .width(processed.width())
              .height(processed.height())
              .byteSize(processed.full().length)
              .sha256(processed.sha256())
              .sortOrder(0)
              .build());
        });

    replaceAvatar(user, media);
    ViewerContext self = new ViewerContext(user.getPublicUid(), user.getId(), false);
    return toPhoto(media, self);
  }

  @Transactional
  public void deleteAvatar(UUID viewerPublicUid) {
    if (viewerPublicUid == null) {
      throw new UnauthorizedException(ErrorCode.AVATAR_REQUIRES_LOGIN);
    }
    AppUser user = appUserRepository.findByPublicUid(viewerPublicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.ACCOUNT_GONE));
    replaceAvatar(user, null);
  }

  /** Zapíše (nebo smaže) {@code user_profile.avatar_media_id} a starý avatar odstraní — soubor i řádek. */
  private void replaceAvatar(AppUser user, Media newMedia) {
    UserProfile profile = userProfileRepository.findById(user.getId())
        .orElseGet(() -> UserProfile.builder().userId(user.getId()).build());
    Long oldAvatarId = profile.getAvatarMediaId();
    Long newAvatarId = newMedia == null ? null : newMedia.getId();
    profile.setAvatarMediaId(newAvatarId);
    userProfileRepository.save(profile);

    if (oldAvatarId != null && !oldAvatarId.equals(newAvatarId)) {
      mediaRepository.findById(oldAvatarId).ifPresent(old -> {
        mediaStorage.delete(old.getStorageKey());
        mediaRepository.delete(old);
      });
    }
  }

  private void requireRecordExists(RecordType recordType, Long recordId) {
    boolean exists = switch (recordType) {
      case PRODUCT -> productRepository.existsById(recordId);
      case STORE -> storeRepository.existsById(recordId);
      case PHOTO -> false;
      // Sem se za normálních okolností nedostane (viz guard v upload() výš) — větev je tu jen
      // kvůli exhaustivitě switche, ne jako podporovaná cesta.
      case USER -> appUserRepository.existsById(recordId);
    };
    if (!exists) {
      throw new NotFoundException(ErrorCode.PHOTO_TARGET_RECORD_NOT_FOUND);
    }
  }

  private String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }
}
