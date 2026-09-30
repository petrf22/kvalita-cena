package cz.kvalitacena.service;

import cz.kvalitacena.config.ReceiptProperties;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.Receipt;
import cz.kvalitacena.db.entity.ReceiptImage;
import cz.kvalitacena.db.entity.ReceiptJob;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.ReceiptImageRepository;
import cz.kvalitacena.db.repo.ReceiptJobRepository;
import cz.kvalitacena.db.repo.ReceiptLineRepository;
import cz.kvalitacena.db.repo.ReceiptRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.TooManyRequestsException;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Účtenka z pohledu vlastníka — nahrání, výpis, smazání, obrázky (docs/soukromi.md,
 * „Účtenky"). Účtenku vidí VÝHRADNĚ vlastník (a worker obrázky ve frontě) — cizí účtenka se
 * tváří jako neexistující (NOT_FOUND, ne FORBIDDEN), stejně jako skryté fotky.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptService {

  private final ReceiptRepository receiptRepository;
  private final ReceiptImageRepository imageRepository;
  private final ReceiptLineRepository lineRepository;
  private final ReceiptJobRepository jobRepository;
  private final AppUserRepository appUserRepository;
  private final ImageProcessingService imageProcessingService;
  private final MediaStorage mediaStorage;
  private final ReceiptProperties receiptProperties;

  public record ReceiptPage(List<Receipt> items, int totalCount, boolean hasMore) {
  }

  public record ReceiptSummary(int unseenCount, int consentVersion) {
  }

  @Transactional
  public Receipt upload(UUID ownerPublicUid, List<byte[]> images, Integer consentVersion,
      boolean archiveConsent, OffsetDateTime capturedAt) {
    AppUser owner = requireUser(ownerPublicUid);
    // Souhlas je verzovaný (docs/soukromi.md) — klient se starým textem souhlasu musí uživatele
    // požádat znovu, jinak by účtenku zpracoval někdo, s kým uživatel nesouhlasil.
    if (consentVersion == null || consentVersion != receiptProperties.getConsentVersion()) {
      throw new ValidationException(ErrorCode.RECEIPT_CONSENT_REQUIRED, receiptProperties.getConsentVersion());
    }
    if (images == null || images.isEmpty()) {
      throw new ValidationException(ErrorCode.RECEIPT_IMAGES_REQUIRED);
    }
    if (images.size() > receiptProperties.getMaxImagesPerReceipt()) {
      throw new ValidationException(ErrorCode.RECEIPT_TOO_MANY_IMAGES, receiptProperties.getMaxImagesPerReceipt());
    }
    // Počítá se z DB, ne v paměti — limit přežije restart a platí i napříč instancemi.
    long today = receiptRepository.countByOwnerUserIdAndCreatedAtAfter(owner.getId(), OffsetDateTime.now().minusDays(1));
    if (today >= receiptProperties.getMaxReceiptsPerDay()) {
      throw new TooManyRequestsException(ErrorCode.RECEIPT_DAILY_LIMIT, receiptProperties.getMaxReceiptsPerDay());
    }

    // Nejdřív zpracovat všechny obrázky (validace + překreslení z pixelů), teprve pak cokoli
    // zapsat — vadná pátá část nesmí nechat na disku čtyři osiřelé soubory.
    List<ImageProcessingService.ProcessedReceiptImage> processed = images.stream()
        .map(raw -> imageProcessingService.processReceipt(raw, receiptProperties.getMaxImageBytes(),
            receiptProperties.getMaxWidth()))
        .toList();

    Receipt receipt = receiptRepository.save(Receipt.builder()
        .ownerUserId(owner.getId())
        .consentVersion(consentVersion)
        .archiveConsent(archiveConsent)
        .capturedAt(capturedAt)
        .build());

    List<String> storedKeys = new ArrayList<>();
    registerCleanupOnRollback(storedKeys);
    short seq = 0;
    for (ImageProcessingService.ProcessedReceiptImage image : processed) {
      String key = mediaStorage.storeReceiptImage(image.content());
      storedKeys.add(key);
      imageRepository.save(ReceiptImage.builder()
          .receiptId(receipt.getId())
          .seq(seq++)
          .storageKey(key)
          .width(image.width())
          .height(image.height())
          .bytes(image.content().length)
          .sha256(image.sha256())
          .build());
    }
    jobRepository.save(ReceiptJob.builder().receiptId(receipt.getId()).build());
    log.info("Účtenka {} nahrána ({} částí)", receipt.getId(), processed.size());
    return receipt;
  }

  @Transactional(readOnly = true)
  public ReceiptPage myReceipts(UUID ownerPublicUid, Integer first, Integer offset) {
    AppUser owner = requireUser(ownerPublicUid);
    int limit = Math.max(1, Math.min(first == null ? 20 : first, 100));
    int skip = Math.max(0, offset == null ? 0 : offset);
    List<Receipt> items = receiptRepository.findPageByOwner(owner.getId(), limit, skip);
    int total = (int) receiptRepository.countByOwnerUserId(owner.getId());
    return new ReceiptPage(items, total, skip + items.size() < total);
  }

  @Transactional(readOnly = true)
  public Receipt get(UUID ownerPublicUid, Long receiptId) {
    AppUser owner = requireUser(ownerPublicUid);
    return receiptRepository.findByIdAndOwnerUserId(receiptId, owner.getId())
        .orElseThrow(() -> new NotFoundException(ErrorCode.RECEIPT_NOT_FOUND));
  }

  @Transactional(readOnly = true)
  public ReceiptSummary summary(UUID ownerPublicUid) {
    AppUser owner = requireUser(ownerPublicUid);
    return new ReceiptSummary((int) receiptRepository.countUnseen(owner.getId()),
        receiptProperties.getConsentVersion());
  }

  @Transactional
  public Receipt markSeen(UUID ownerPublicUid, Long receiptId) {
    Receipt receipt = get(ownerPublicUid, receiptId);
    receipt.setSeenAt(OffsetDateTime.now());
    return receiptRepository.save(receipt);
  }

  /**
   * Smaže účtenku včetně obrázků a řádků. Ceny, které z ní uživatel už potvrdil, zůstávají
   * jako běžné cenové záznamy — {@code receipt_line_id} se nuluje (ON DELETE SET NULL),
   * stejná úvaha jako u výmazu účtu (docs/soukromi.md, „GDPR").
   */
  @Transactional
  public void delete(UUID ownerPublicUid, Long receiptId) {
    Receipt receipt = get(ownerPublicUid, receiptId);
    deleteImageFiles(receipt.getId());
    receiptRepository.delete(receipt);
    log.info("Účtenka {} smazána vlastníkem", receiptId);
  }

  /** Obrázek pro vlastníka. Prázdný výsledek = neexistuje, cizí, nebo už smazaný retencí. */
  @Transactional(readOnly = true)
  public Optional<byte[]> ownerImage(UUID ownerPublicUid, Long receiptId, int seq) {
    if (ownerPublicUid == null) {
      return Optional.empty();
    }
    return appUserRepository.findByPublicUid(ownerPublicUid)
        .flatMap(owner -> receiptRepository.findByIdAndOwnerUserId(receiptId, owner.getId()))
        .flatMap(r -> loadImage(r.getId(), seq));
  }

  @Transactional(readOnly = true)
  public List<ReceiptImage> images(Long receiptId) {
    return imageRepository.findByReceiptIdOrderBySeqAsc(receiptId);
  }

  Optional<byte[]> loadImage(Long receiptId, int seq) {
    return imageRepository.findByReceiptIdAndSeq(receiptId, (short) seq)
        .flatMap(image -> mediaStorage.load(image.getStorageKey(), MediaStorage.Variant.FULL));
  }

  /** Soubory na disku i řádky receipt_image — hlavička a řádky účtenky zůstávají. */
  void deleteImageFiles(Long receiptId) {
    for (ReceiptImage image : imageRepository.findByReceiptIdOrderBySeqAsc(receiptId)) {
      mediaStorage.delete(image.getStorageKey());
    }
    imageRepository.deleteByReceiptId(receiptId);
  }

  /** Pro výmaz účtu (AccountService) — soubory musí zmizet dřív, než kaskáda smaže řádky. */
  public void deleteAllImageFilesOf(Long ownerUserId) {
    for (Receipt receipt : receiptRepository.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId)) {
      for (ReceiptImage image : imageRepository.findByReceiptIdOrderBySeqAsc(receipt.getId())) {
        mediaStorage.delete(image.getStorageKey());
      }
    }
  }

  private AppUser requireUser(UUID publicUid) {
    if (publicUid == null) {
      throw new UnauthorizedException(ErrorCode.RECEIPT_REQUIRES_LOGIN);
    }
    return appUserRepository.findByPublicUid(publicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.RECEIPT_REQUIRES_LOGIN));
  }

  /** Soubory zapsané před selháním transakce by jinak zůstaly na disku bez řádku v DB. */
  private void registerCleanupOnRollback(List<String> storedKeys) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCompletion(int status) {
        if (status != STATUS_COMMITTED) {
          storedKeys.forEach(mediaStorage::delete);
        }
      }
    });
  }
}
