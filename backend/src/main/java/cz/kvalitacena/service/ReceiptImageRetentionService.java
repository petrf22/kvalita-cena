package cz.kvalitacena.service;

import cz.kvalitacena.config.ReceiptProperties;
import cz.kvalitacena.db.entity.Receipt;
import cz.kvalitacena.db.entity.ReceiptProcessingStatus;
import cz.kvalitacena.db.repo.ReceiptJobRepository;
import cz.kvalitacena.db.repo.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Denní retence obrázků účtenek (docs/soukromi.md, „Účtenky") — obrázky jsou jen prostředek
 * k přečtení účtenky, ne trvalý záznam; hlavička a řádky zůstávají. Účtenky se souhlasem
 * s archivací čekají, až si je worker stáhne, nejvýš {@code archive-grace-days} navíc.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptImageRetentionService {

  private final ReceiptRepository receiptRepository;
  private final ReceiptJobRepository jobRepository;
  private final ReceiptService receiptService;
  private final ReceiptProperties receiptProperties;

  @Scheduled(cron = "@daily")
  @Transactional
  public void deleteExpiredImages() {
    OffsetDateTime now = OffsetDateTime.now();
    OffsetDateTime cutoff = now.minusDays(receiptProperties.getImageRetentionDays());
    OffsetDateTime archiveCutoff = cutoff.minusDays(receiptProperties.getArchiveGraceDays());
    OffsetDateTime staleCutoff = now.minusDays(receiptProperties.getStaleImageDays());
    List<Receipt> expired = receiptRepository.findImagesToDelete(cutoff, archiveCutoff, staleCutoff);
    for (Receipt receipt : expired) {
      receiptService.deleteImageFiles(receipt.getId());
      receipt.setImagesDeletedAt(now);
      if (!receipt.getProcessingStatus().isFinished()) {
        // Worker ji nestihl přečíst — bez obrázků už ani nestihne.
        receipt.setProcessingStatus(ReceiptProcessingStatus.FAILED);
        receipt.setProcessedAt(now);
        jobRepository.findByReceiptId(receipt.getId()).ifPresent(job -> job.setProcessedAt(now));
      }
    }
    if (!expired.isEmpty()) {
      log.info("Retence účtenek: smazány obrázky u {} účtenek", expired.size());
    }
  }
}
