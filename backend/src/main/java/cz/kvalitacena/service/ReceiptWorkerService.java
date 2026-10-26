package cz.kvalitacena.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.kvalitacena.config.ReceiptProperties;
import cz.kvalitacena.controller.worker.WorkerArchiveCandidate;
import cz.kvalitacena.controller.worker.WorkerReceiptJob;
import cz.kvalitacena.controller.worker.WorkerReceiptResult;
import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.QuantityUnit;
import cz.kvalitacena.db.entity.Receipt;
import cz.kvalitacena.db.entity.ReceiptImage;
import cz.kvalitacena.db.entity.ReceiptJob;
import cz.kvalitacena.db.entity.ReceiptLine;
import cz.kvalitacena.db.entity.ReceiptLineStatus;
import cz.kvalitacena.db.entity.ReceiptMatchSource;
import cz.kvalitacena.db.entity.ReceiptProcessingStatus;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.ReceiptImageRepository;
import cz.kvalitacena.db.repo.ReceiptJobRepository;
import cz.kvalitacena.db.repo.ReceiptLineRepository;
import cz.kvalitacena.db.repo.ReceiptRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Serverová strana pull workeru (docs/ai.md, „Kde to běží" a „Účtenky"). Worker si úlohy
 * vyzvedává s leasem, výsledek zapíše jako NÁVRH (řádky účtenky) — cenu nezakládá nikdy.
 * Když worker neběží, účtenky jen čekají ve frontě, appka funguje beze změny.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptWorkerService {

  private static final int MAX_TEXT = 200;
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

  private final ReceiptJobRepository jobRepository;
  private final ReceiptRepository receiptRepository;
  private final ReceiptImageRepository imageRepository;
  private final ReceiptLineRepository lineRepository;
  private final StoreRepository storeRepository;
  private final ReceiptMatchingService matchingService;
  private final ReceiptService receiptService;
  private final ReceiptProperties receiptProperties;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Transactional
  public List<WorkerReceiptJob> lease(Integer requested) {
    int limit = Math.max(1, Math.min(requested == null ? 1 : requested, receiptProperties.getMaxLeaseBatch()));
    OffsetDateTime now = OffsetDateTime.now();
    // Na milisekundy: konec lease se workeru vrací jako identifikátor pronájmu (fail) a musí
    // se po cestě JSON → DB (mikrosekundy) porovnat přesně.
    OffsetDateTime leaseUntil = now.plusMinutes(receiptProperties.getLeaseMinutes()).truncatedTo(ChronoUnit.MILLIS);
    List<WorkerReceiptJob> result = new ArrayList<>();
    for (ReceiptJob job : jobRepository.lockAvailable(now, limit)) {
      Optional<Receipt> receipt = receiptRepository.findById(job.getReceiptId());
      if (receipt.isEmpty() || receipt.get().getImagesDeletedAt() != null) {
        // Obrázky zmizely (retence nezpracované účtenky) — není co číst.
        job.setProcessedAt(now);
        job.setLastError("images gone");
        receipt.ifPresent(r -> finish(r, ReceiptProcessingStatus.FAILED, now));
        continue;
      }
      if (job.getAttempts() >= receiptProperties.getMaxAttempts()) {
        // Vrátila se jen proto, že vypršel lease — worker na ní spadl nebo zamrzl bez /fail.
        // Bez téhle větve by se vyzvedávala dokola a limit pokusů by neplatil nikdy.
        job.setProcessedAt(now);
        job.setLeaseUntil(null);
        job.setLastError("lease expired after " + job.getAttempts() + " attempts");
        finish(receipt.get(), ReceiptProcessingStatus.FAILED, now);
        continue;
      }
      job.setLeasedAt(now);
      job.setLeaseUntil(leaseUntil);
      job.setAttempts((short) (job.getAttempts() + 1));
      Receipt r = receipt.get();
      r.setProcessingStatus(ReceiptProcessingStatus.PROCESSING);
      result.add(new WorkerReceiptJob(r.getId(), r.getCapturedAt(), leaseUntil,
          imagePaths(r.getId(), imageRepository.findByReceiptIdOrderBySeqAsc(r.getId()))));
    }
    return result;
  }

  @Transactional
  public void submitResult(Long receiptId, WorkerReceiptResult result) {
    ReceiptJob job = jobRepository.findForUpdateByReceiptId(receiptId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.RECEIPT_NOT_FOUND));
    Receipt receipt = receiptRepository.findById(receiptId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.RECEIPT_NOT_FOUND));
    if (result == null || result.status() == null || !result.status().isFinished()
        || result.status() == ReceiptProcessingStatus.FAILED) {
      throw new ValidationException(ErrorCode.RECEIPT_RESULT_INVALID);
    }
    if (job.getProcessedAt() != null) {
      // Výsledek už přišel (worker nedostal odpověď a poslal ho znovu, nebo pozdě po vypršeném
      // leasu) nebo úloha skončila jako FAILED — první zápis platí, řádky se nepřepisují.
      log.warn("Výsledek účtenky {} přišel k už ukončené úloze — ignoruje se", receiptId);
      return;
    }
    OffsetDateTime now = OffsetDateTime.now();
    job.setProcessedAt(now);
    job.setLeaseUntil(null);
    job.setModel(truncate(result.model(), 100));
    job.setRawText(result.rawText());
    job.setExtraction(toJson(result.extraction()));
    job.setLastError(null);

    // Druhá pojistka k processedAt výš: řádky, na které uživatel sáhl, stroj nikdy nepřepisuje.
    List<ReceiptLine> existing = lineRepository.findByReceiptIdOrderByLineNoAsc(receiptId);
    boolean userTouched = existing.stream().anyMatch(l -> l.isConfirmed()
        || l.getStatus() == ReceiptLineStatus.MATCHED
        || l.getMatchSource() == ReceiptMatchSource.USER
        || l.getMatchSource() == ReceiptMatchSource.NEW_PRODUCT
        || l.getStatus() == ReceiptLineStatus.IGNORED);
    if (userTouched) {
      log.warn("Výsledek účtenky {} přišel znovu po zásahu uživatele — řádky se nemění", receiptId);
      return;
    }
    lineRepository.deleteAll(existing);
    lineRepository.flush();

    if (result.status() != ReceiptProcessingStatus.PROCESSED) {
      finish(receipt, result.status(), now);
      return;
    }

    receipt.setStoreNameRaw(truncate(result.storeName(), 200));
    receipt.setStoreAddressRaw(truncate(result.storeAddress(), 300));
    receipt.setStoreIcoRaw(truncate(result.storeIco(), 20));
    // Budoucí datum je chyba čtení (nebo špatné hodiny pokladny) — lepší prázdné pole, které
    // uživatel doplní, než cena zapsaná „zítra".
    receipt.setPurchasedAt(result.purchasedAt() != null && result.purchasedAt().isAfter(now.plusHours(1))
        ? null : result.purchasedAt());
    receipt.setTotalAmount(amount(result.totalAmount()));
    receipt.setCurrency(result.currency() != null && result.currency().matches("[A-Z]{3}") ? result.currency() : null);

    List<ReceiptLine> lines = new ArrayList<>();
    short lineNo = 0;
    for (WorkerReceiptResult.Line in : Optional.ofNullable(result.lines()).orElse(List.of())) {
      if (lineNo >= receiptProperties.getMaxLines()) {
        break;
      }
      String label = in == null ? null : truncate(in.label(), MAX_TEXT);
      if (label == null || label.isBlank()) {
        continue;
      }
      lines.add(ReceiptLine.builder()
          .receiptId(receiptId)
          .lineNo(lineNo++)
          .labelRaw(label.trim())
          .internalCode(truncate(in.internalCode(), 40))
          .quantity(quantity(in.quantity()))
          .quantityUnit(in.quantityUnit() != null ? in.quantityUnit() : QuantityUnit.PCS)
          .unitPrice(amount(in.unitPrice()))
          .lineTotal(amount(in.lineTotal()))
          .discountAmount(amount(in.discountAmount() == null ? null : in.discountAmount().abs()))
          .priceKindGuess(in.priceKind() != null ? in.priceKind() : PriceKind.REGULAR)
          .build());
    }

    Optional<Store> store = matchingService.suggestStore(receipt);
    receipt.setSuggestedStoreId(store.map(Store::getId).orElse(null));
    matchingService.matchLines(lines, store.orElse(null), receipt.getOwnerUserId());
    lineRepository.saveAll(lines);
    finish(receipt, ReceiptProcessingStatus.PROCESSED, now);
    log.info("Účtenka {} zpracována ({} řádků, model {})", receiptId, lines.size(), job.getModel());
  }

  @Transactional
  public void fail(Long receiptId, String error, OffsetDateTime leaseUntil) {
    ReceiptJob job = jobRepository.findForUpdateByReceiptId(receiptId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.RECEIPT_NOT_FOUND));
    if (job.getProcessedAt() != null) {
      // Výsledek už je uložený, jen worker nedostal odpověď na /result a hlásí chybu —
      // hotovou účtenku nesmí vrátit do fronty (processed_at by ji z lockAvailable vyřadil).
      log.warn("Chyba účtenky {} nahlášená k už ukončené úloze — ignoruje se", receiptId);
      return;
    }
    if (leaseUntil != null && (job.getLeaseUntil() == null || !leaseUntil.isEqual(job.getLeaseUntil()))) {
      // Hlášení ze staršího, mezitím vypršelého pronájmu — úlohu už má jiný pokus a ten by
      // chyba cizího pokusu vrátila do fronty uprostřed práce. Bez leaseUntil (starší worker)
      // platí jen kontrola dokončení výš.
      log.warn("Chyba účtenky {} z neplatného pronájmu — ignoruje se", receiptId);
      return;
    }
    job.setLeaseUntil(null);
    job.setLastError(truncate(error, 500));
    Receipt receipt = receiptRepository.findById(receiptId).orElse(null);
    if (job.getAttempts() >= receiptProperties.getMaxAttempts()) {
      job.setProcessedAt(OffsetDateTime.now());
      if (receipt != null) {
        finish(receipt, ReceiptProcessingStatus.FAILED, OffsetDateTime.now());
      }
    } else if (receipt != null) {
      receipt.setProcessingStatus(ReceiptProcessingStatus.QUEUED);
    }
  }

  /** Obrázek pro worker — jen u účtenky, která je ve frontě nebo čeká na archivaci. */
  @Transactional(readOnly = true)
  public Optional<byte[]> image(Long receiptId, int seq) {
    Optional<Receipt> receipt = receiptRepository.findById(receiptId);
    boolean inQueue = receipt.map(r -> !r.getProcessingStatus().isFinished()).orElse(false);
    boolean forArchive = receipt.map(r -> r.isArchiveConsent() && r.getArchivedAt() == null).orElse(false);
    if (!inQueue && !forArchive) {
      return Optional.empty();
    }
    return receiptService.loadImage(receiptId, seq);
  }

  @Transactional(readOnly = true)
  public List<WorkerArchiveCandidate> archiveCandidates(Integer requested) {
    int limit = Math.max(1, Math.min(requested == null ? 20 : requested, 100));
    return receiptRepository.findArchiveCandidates(limit).stream()
        .map(r -> new WorkerArchiveCandidate(r.getId(),
            imagePaths(r.getId(), imageRepository.findByReceiptIdOrderBySeqAsc(r.getId()))))
        .toList();
  }

  @Transactional
  public void markArchived(Long receiptId) {
    Receipt receipt = receiptRepository.findById(receiptId)
        .filter(Receipt::isArchiveConsent)
        .orElseThrow(() -> new NotFoundException(ErrorCode.RECEIPT_NOT_FOUND));
    receipt.setArchivedAt(OffsetDateTime.now());
  }

  private void finish(Receipt receipt, ReceiptProcessingStatus status, OffsetDateTime now) {
    receipt.setProcessingStatus(status);
    receipt.setProcessedAt(now);
    receipt.setSeenAt(null);
  }

  /** Relativní cesty — worker si před ně dá vlastní base URL (za proxy server svou adresu nezná). */
  private static List<String> imagePaths(Long receiptId, List<ReceiptImage> images) {
    return images.stream()
        .map(i -> "/api/worker/receipts/" + receiptId + "/images/" + i.getSeq())
        .toList();
  }

  private String toJson(Object extraction) {
    if (extraction == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(extraction);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
  }

  private static BigDecimal amount(BigDecimal value) {
    if (value == null || value.abs().compareTo(MAX_AMOUNT) > 0) {
      return null;
    }
    return value.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  private static BigDecimal quantity(BigDecimal value) {
    if (value == null || value.signum() <= 0 || value.compareTo(new BigDecimal("9999999")) > 0) {
      return null;
    }
    return value.setScale(3, java.math.RoundingMode.HALF_UP);
  }
}
