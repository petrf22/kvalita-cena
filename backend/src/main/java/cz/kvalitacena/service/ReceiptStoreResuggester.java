package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.Receipt;
import cz.kvalitacena.db.entity.ReceiptLine;
import cz.kvalitacena.db.entity.ReceiptProcessingStatus;
import cz.kvalitacena.db.entity.ReceiptReviewStatus;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.ReceiptLineRepository;
import cz.kvalitacena.db.repo.ReceiptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Obchod potvrzený u jedné účtenky (často právě založený z její hlavičky) může patřit i dalším
 * účtenkám vlastníka, které se rozpoznaly dřív, než obchod existoval — jejich návrh se proto
 * přepočítá ({@code ReceiptReviewService.updateHeader}, docs/ai.md, „Návrh obchodu"). Při změně
 * návrhu se znovu spárují i strojové návrhy řádků, protože naučená označení a vnitroobchodní
 * kódy závisí na obchodě; ruční volby a zrušené návrhy {@code matchLines} respektuje.
 *
 * <p>Běží až PO commitu potvrzení a na pozadí ({@code receiptTaskExecutor}), každá účtenka ve
 * vlastní transakci s blokujícím zámkem. Ve vlákně requestu běžet nemůže — čekání na zámky
 * ostatních účtenek a párování řádků (i s dotazy do Open Food Facts) by odpověď na už uložené
 * potvrzení protáhlo přes timeout klienta. Přeskočit zamčenou účtenku (SKIP LOCKED) nejde —
 * zámek drží i matchLine/unmatchLine a worker, které návrh po potvrzení samy nepřepočítají,
 * a účtenka by zůstala bez návrhu natrvalo. Čekat na zámek uvnitř transakce updateHeader zase
 * nejde: ta drží zámek své účtenky a dvě souběžná potvrzení by se zablokovala navzájem. Po
 * commitu se drží vždy jen jeden zámek.
 */
@Slf4j
@Service
public class ReceiptStoreResuggester {

  private final ReceiptRepository receiptRepository;
  private final ReceiptLineRepository lineRepository;
  private final ReceiptMatchingService matchingService;
  private final PlatformTransactionManager transactionManager;
  private final TaskExecutor executor;

  public ReceiptStoreResuggester(ReceiptRepository receiptRepository, ReceiptLineRepository lineRepository,
      ReceiptMatchingService matchingService, PlatformTransactionManager transactionManager,
      @Qualifier("receiptTaskExecutor") TaskExecutor executor) {
    this.receiptRepository = receiptRepository;
    this.lineRepository = lineRepository;
    this.matchingService = matchingService;
    this.transactionManager = transactionManager;
    this.executor = executor;
  }

  /** Naplánuje přepočet otevřených účtenek vlastníka (kromě {@code exceptReceiptId}) po commitu. */
  public void resuggestOpenReceiptsAfterCommit(Long ownerId, Long exceptReceiptId) {
    Runnable submit = () -> submit(ownerId, exceptReceiptId);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          submit.run();
        }
      });
    } else {
      submit.run();
    }
  }

  private void submit(Long ownerId, Long exceptReceiptId) {
    try {
      executor.execute(() -> resuggestAll(ownerId, exceptReceiptId));
    } catch (TaskRejectedException e) {
      // Výjimka z afterCommit by klientovi ohlásila chybu potvrzení, které už je uložené. Návrh
      // je jen pohodlí — přepočítá se při dalším potvrzení obchodu.
      log.warn("Přepočet návrhu obchodu po potvrzení účtenky {} se nevešel do fronty: {}", exceptReceiptId,
          e.getMessage());
    }
  }

  private void resuggestAll(Long ownerId, Long exceptReceiptId) {
    List<Long> ids;
    try {
      ids = tx().execute(status -> receiptRepository.findOpenWithoutStoreIds(ownerId, exceptReceiptId));
    } catch (RuntimeException e) {
      log.warn("Výběr účtenek k přepočtu návrhu obchodu (vlastník {}) selhal: {}", ownerId, e.getMessage());
      return;
    }
    for (Long id : ids) {
      try {
        tx().executeWithoutResult(status -> resuggest(id, ownerId));
      } catch (RuntimeException e) {
        // Návrh je jen pohodlí — potvrzení obchodu už platí a selhání u jedné účtenky nesmí
        // zastavit ostatní.
        log.warn("Přepočet návrhu obchodu u účtenky {} selhal: {}", id, e.getMessage());
      }
    }
  }

  /** Vždy nová transakce — i kdyby úloha výjimečně běžela ve vlákně s navázanou transakcí. */
  private TransactionTemplate tx() {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return tx;
  }

  private void resuggest(Long receiptId, Long ownerId) {
    // Zámek počká i na commit workeru, který účtenku právě dokončuje — PostgreSQL pak vrátí
    // čerstvou verzi řádku, už PROCESSED s návrhem spočítaným před potvrzením obchodu.
    Receipt other = receiptRepository.findForUpdateById(receiptId).orElse(null);
    // Mezi výběrem a zámkem mohl vlastník obchod potvrdit nebo účtenku uzavřít. Pořád PROCESSING
    // znamená, že worker výsledek ještě neposlal: submitResult účtenku zamyká DŘÍV, než návrh
    // počítá, takže ho spočítá až po tomhle zámku — a potvrzení obchodu už uvidí.
    if (other == null || !ownerId.equals(other.getOwnerUserId()) || other.getStoreId() != null
        || other.getProcessingStatus() != ReceiptProcessingStatus.PROCESSED
        || other.getReviewStatus() == ReceiptReviewStatus.DONE) {
      return;
    }
    Optional<Store> suggested = matchingService.suggestStore(other);
    Long suggestedId = suggested.map(Store::getId).orElse(null);
    if (Objects.equals(suggestedId, other.getSuggestedStoreId())) {
      return;
    }
    other.setSuggestedStoreId(suggestedId);
    List<ReceiptLine> lines = lineRepository.findByReceiptIdOrderByLineNoAsc(receiptId);
    matchingService.matchLines(lines, suggested.orElse(null), ownerId);
    lineRepository.saveAll(lines);
    receiptRepository.save(other);
  }
}
