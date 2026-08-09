package cz.kvalitacena.service;

import cz.kvalitacena.config.ModerationProperties;
import cz.kvalitacena.controller.FlagResult;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.RecordType;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.MediaRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.RecordFlagRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Nahlášení záznamu jako podezřelého/nesmyslného — hlasuje se o FAKTU, nikdy o ČLOVĚKU
 * (docs/reputace.md, "Nesouhlas se vyjadřuje k faktu, ne k člověku"). Po dosažení prahu
 * {@code app.moderation.flags-to-hide} různých nahlášení se záznam skryje (core.product.hidden_at
 * / core.store.hidden_at) a čeká na přezkum — dál ho vidí jen autor (viz ProductGraphQlController,
 * StoreGraphQlController).
 */
@Service
@RequiredArgsConstructor
public class RecordFlagService {

  private final RecordFlagRepository recordFlagRepository;
  private final AppUserRepository appUserRepository;
  private final ProductRepository productRepository;
  private final StoreRepository storeRepository;
  private final MediaRepository mediaRepository;
  private final ModerationProperties moderationProperties;

  @Transactional
  public FlagResult flag(RecordType recordType, Long recordId, String reason, UUID viewerPublicUid) {
    if (viewerPublicUid == null) {
      throw new UnauthorizedException("Nahlášení vyžaduje přihlášení");
    }
    AppUser user = appUserRepository.findByPublicUid(viewerPublicUid)
        .orElseThrow(() -> new UnauthorizedException("Účet už neexistuje"));
    requireRecordExists(recordType, recordId);

    // ON CONFLICT DO NOTHING nad uq_record_flag_user — druhý hlas téhož člověka nic nezmění.
    recordFlagRepository.insertIgnoringDuplicate(recordType.name(), recordId, user.getId(), blankToNull(reason));

    long flagCount = recordFlagRepository.countByRecordTypeAndRecordId(recordType, recordId);
    boolean hidden = flagCount >= flagsToHide(recordType);
    if (hidden) {
      hideRecord(recordType, recordId);
    }
    return new FlagResult(flagCount, hidden);
  }

  /**
   * Fotka je nejrizikovější uživatelský obsah v appce — cena přehlédnutého nevhodného
   * obrázku je vyšší než cena zbytečně skryté dobré fotky (dá se nahrát znovu), proto mnohem
   * nižší práh než u zboží/obchodu (docs/reputace.md).
   */
  private int flagsToHide(RecordType recordType) {
    return recordType == RecordType.PHOTO
        ? moderationProperties.getPhotoFlagsToHide()
        : moderationProperties.getFlagsToHide();
  }

  private void requireRecordExists(RecordType recordType, Long recordId) {
    boolean exists = switch (recordType) {
      case PRODUCT -> productRepository.existsById(recordId);
      case STORE -> storeRepository.existsById(recordId);
      case PHOTO -> mediaRepository.existsById(recordId);
    };
    if (!exists) {
      throw new NotFoundException("Nahlašovaný záznam neexistuje");
    }
  }

  private void hideRecord(RecordType recordType, Long recordId) {
    switch (recordType) {
      case PRODUCT -> productRepository.findById(recordId).ifPresent(p -> {
        if (p.getHiddenAt() == null) {
          p.setHiddenAt(OffsetDateTime.now());
          productRepository.save(p);
        }
      });
      case STORE -> storeRepository.findById(recordId).ifPresent(s -> {
        if (s.getHiddenAt() == null) {
          s.setHiddenAt(OffsetDateTime.now());
          storeRepository.save(s);
        }
      });
      case PHOTO -> mediaRepository.findById(recordId).ifPresent(m -> {
        if (m.getHiddenAt() == null) {
          m.setHiddenAt(OffsetDateTime.now());
          mediaRepository.save(m);
        }
      });
    }
  }

  private String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }
}
