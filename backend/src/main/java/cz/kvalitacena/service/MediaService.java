package cz.kvalitacena.service;

import cz.kvalitacena.config.MediaProperties;
import cz.kvalitacena.controller.Photo;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.Media;
import cz.kvalitacena.db.entity.PhotoKind;
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
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Fotky zboží a provozoven — nahrávají se VÝHRADNĚ na existující záznam (žádné osiřelé
 * uploady), binární obsah jde přes {@link MediaStorage},
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

  /**
   * @param lang jazyk obalu/etikety na fotce; {@code null} = doplní se jazyk requestu, ale jen
   *             u fotek zboží — u provozoven a avatarů jazyk nic neznamená (docs/lokalizace.md)
   */
  @Transactional
  public Media upload(RecordType recordType, Long recordId, byte[] raw, String caption, PhotoKind kind,
      String lang, UUID viewerPublicUid) {
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
    // z mobilu) vrátí existující řádek místo duplikátu. Druh se u něj NEpřepisuje — je to pořád
    // ta samá fotka, ne nová verze s jiným záměrem.
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
        .photoKind(kind == null ? PhotoKind.OTHER : kind)
        .uploadedByUserId(user.getId())
        .contentType("image/jpeg")
        .width(processed.width())
        .height(processed.height())
        .byteSize(processed.full().length)
        .sha256(processed.sha256())
        .caption(blankToNull(caption))
        .lang(photoLang(recordType, lang))
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
  public Media update(Long mediaId, String caption, Integer sortOrder, PhotoKind kind, String lang,
      UUID viewerPublicUid) {
    Media media = requireOwnMedia(mediaId, viewerPublicUid, ErrorCode.PHOTO_UPDATE_NOT_OWNER);
    if (caption != null) {
      media.setCaption(blankToNull(caption));
    }
    if (sortOrder != null) {
      media.setSortOrder(sortOrder);
    }
    if (kind != null) {
      media.setPhotoKind(kind);
    }
    // Jazyk smí autor opravit, když appka tipla podle jazyka requestu špatně (vyfotil cizí
    // obal). Prázdný řetězec znamená "nevím", tedy návrat na NULL.
    if (lang != null) {
      media.setLang(normalizeLang(lang));
    }
    return mediaRepository.save(media);
  }

  @Transactional(readOnly = true)
  public List<Photo> photosFor(RecordType recordType, Long recordId, ViewerContext viewer) {
    return mediaRepository.findByRecordTypeAndRecordIdOrderBySortOrderAscIdAsc(recordType, recordId).stream()
        .filter(media -> visibleTo(media, viewer))
        .sorted(byViewerLanguage())
        .map(media -> toPhoto(media, viewer))
        .toList();
  }

  /**
   * Fotky v jazyce klienta dopředu, pak ty bez určeného jazyka, pak cizojazyčné — uvnitř
   * každé skupiny zůstává pořadí z DB ({@code sortOrder}, viz kontrakt „první = hlavní").
   * Nic se neskrývá: cizojazyčná etiketa je pořád lepší než žádná, jen nemá být první.
   * U provozoven a avatarů je jazyk vždy NULL, takže se pro ně nic nemění.
   */
  private Comparator<Media> byViewerLanguage() {
    String language = LocaleContextHolder.getLocale().getLanguage();
    return Comparator.comparingInt((Media media) -> {
      String lang = media.getLang();
      if (lang == null) return 1;
      return lang.equals(language) ? 0 : 2;
    });
  }

  /** Dotažení fotek pro víc záznamů najednou (GraphQL {@code @BatchMapping}) — žádné N+1. */
  @Transactional(readOnly = true)
  public Map<Long, List<Photo>> photosForBatch(RecordType recordType, Collection<Long> recordIds, ViewerContext viewer) {
    return mediaRepository.findByRecordTypeAndRecordIdInOrderBySortOrderAscIdAsc(recordType, recordIds).stream()
        .filter(media -> visibleTo(media, viewer))
        .sorted(byViewerLanguage())
        .collect(Collectors.groupingBy(Media::getRecordId,
            Collectors.mapping(media -> toPhoto(media, viewer), Collectors.toList())));
  }

  public Photo toPhoto(Media media, ViewerContext viewer) {
    boolean mine = viewer.userId() != null && media.getUploadedByUserId().equals(viewer.userId());
    return new Photo(media.getId(), "/api/media/" + media.getId(), "/api/media/" + media.getId() + "/thumb",
        media.getWidth(), media.getHeight(), media.getCaption(), mine, media.isHidden(),
        messages.get("attribution.media"), media.getPhotoKind(), media.getLang());
  }

  /**
   * Jazyk se doplňuje jen u fotek zboží. Klient ho posílá explicitně (ví, co fotí); když
   * nepošle nic, vezme se jazyk requestu — u obalu i etikety je to nejlepší dostupný odhad
   * a autor ho může opravit přes {@code updatePhoto}.
   */
  private String photoLang(RecordType recordType, String lang) {
    if (recordType != RecordType.PRODUCT) return null;
    String normalized = normalizeLang(lang);
    return normalized != null ? normalized : LocaleContextHolder.getLocale().getLanguage();
  }

  private String normalizeLang(String lang) {
    if (lang == null || lang.isBlank()) return null;
    String language = Locale.forLanguageTag(lang.trim()).getLanguage();
    return language.isEmpty() ? null : language.toLowerCase(Locale.ROOT);
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
    return !media.isHidden() || media.getUploadedByUserId().equals(viewer.userId()) || viewer.moderator();
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
    ViewerContext self = new ViewerContext(user.getPublicUid(), user.getId(), false, user.isModerator());
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
      // Fotka ani recenze zatím vlastní fotku nést nemůžou (guard v upload() výš) — obě
      // větve jsou tu jen kvůli exhaustivitě switche, ne jako podporovaná cesta.
      case PHOTO, REVIEW -> false;
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
