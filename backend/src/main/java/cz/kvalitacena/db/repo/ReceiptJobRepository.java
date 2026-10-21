package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ReceiptJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ReceiptJobRepository extends JpaRepository<ReceiptJob, Long> {

  Optional<ReceiptJob> findByReceiptId(Long receiptId);

  /**
   * Úloha se zámkem řádku — {@code submitResult} a {@code fail} se tak nepotkají napůl
   * (výsledek uložený, odpověď ztracená, worker pak hlásí chybu téže úlohy).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT j FROM ReceiptJob j WHERE j.receiptId = :receiptId")
  Optional<ReceiptJob> findForUpdateByReceiptId(@Param("receiptId") Long receiptId);

  /**
   * Dávka pro worker: nezpracované úlohy, které nikdo nedrží (nebo komu lease vypršel —
   * vypnutý PC uprostřed práce). SKIP LOCKED kvůli případnému druhému workeru.
   */
  @Query(value = """
      SELECT * FROM ai.receipt_job
      WHERE processed_at IS NULL AND (lease_until IS NULL OR lease_until < :now)
      ORDER BY enqueued_at
      LIMIT :limit
      FOR UPDATE SKIP LOCKED
      """, nativeQuery = true)
  List<ReceiptJob> lockAvailable(@Param("now") OffsetDateTime now, @Param("limit") int limit);
}
