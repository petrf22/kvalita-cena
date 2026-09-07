package cz.kvalitacena.service;

import cz.kvalitacena.config.PrivacyProperties;
import cz.kvalitacena.config.ReceiptProperties;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.ProductAliasConfirmationRepository;
import cz.kvalitacena.db.repo.ProductStoreLabelConfirmationRepository;
import cz.kvalitacena.db.repo.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Denní pseudonymizace vazby observace → uživatel (docs/soukromi.md, "Retence vazby observace →
 * uživatel: 180 dní") — bez tohoto jobu appka poruší vlastní slib, že „moje příspěvky" ukazují
 * jen posledních 180 dní, o kus víc s každým dnem provozu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PseudonymizationService {

  private final PriceObservationRepository priceObservationRepository;
  private final ProductAliasConfirmationRepository aliasConfirmationRepository;
  private final ProductStoreLabelConfirmationRepository labelConfirmationRepository;
  private final ReceiptRepository receiptRepository;
  private final PrivacyProperties privacyProperties;
  private final ReceiptProperties receiptProperties;

  @Scheduled(cron = "@daily")
  @Transactional
  public void pseudonymizeOldObservations() {
    OffsetDateTime cutoff = OffsetDateTime.now().minusDays(privacyProperties.getPseudonymizationDays());
    int affected = priceObservationRepository.pseudonymizeObservationsBefore(cutoff);
    int aliasConfirmations = aliasConfirmationRepository.pseudonymizeBefore(cutoff);
    int labelConfirmations = labelConfirmationRepository.pseudonymizeBefore(cutoff);
    if (affected > 0) {
      log.info("Pseudonymizace: zrušena vazba na uživatele u {} observací starších {} dní.",
          affected, privacyProperties.getPseudonymizationDays());
    }
    if (aliasConfirmations > 0) {
      log.info("Pseudonymizace: zrušena vazba na uživatele u {} potvrzení názvových aliasů.",
          aliasConfirmations);
    }
    if (labelConfirmations > 0) {
      log.info("Pseudonymizace: zrušena vazba na uživatele u {} potvrzení obchodních označení.",
          labelConfirmations);
    }
    deleteOldReceipts();
  }

  /**
   * Účtenka se na rozdíl od cenového zápisu NEpseudonymizuje, ale maže celá (docs/soukromi.md,
   * „Účtenka"): ceny z ní už žijí ve vlastních observacích a to, co zbývá, je přehled cizího
   * nákupu — bez majitele nemá hodnotu, jen riziko. Vlastní lhůta, ne
   * {@code app.privacy.pseudonymization-days}: tohle je retence obsahu, ne délka vazby na účet.
   */
  private void deleteOldReceipts() {
    OffsetDateTime cutoff = OffsetDateTime.now().minusDays(receiptProperties.getRetentionDays());
    int receipts = receiptRepository.deleteOlderThan(cutoff);
    if (receipts > 0) {
      log.info("Retence: smazáno {} účtenek starších {} dní.", receipts,
          receiptProperties.getRetentionDays());
    }
  }
}
