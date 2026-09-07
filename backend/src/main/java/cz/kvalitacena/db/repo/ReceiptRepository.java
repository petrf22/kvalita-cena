package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.Receipt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {

  /** Idempotence importu — tentýž dokument nahraný podruhé vrátí existující účtenku. */
  Optional<Receipt> findByUploadedByUserIdAndPayloadSha256(Long userId, byte[] payloadSha256);

  List<Receipt> findByUploadedByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

  long countByUploadedByUserIdAndCreatedAtAfter(Long userId, OffsetDateTime after);

  /**
   * Účtenky nahrané jedním člověkem se po 180 dnech nepseudonymizují, ale MAŽOU
   * (docs/soukromi.md, „Účtenka") — na rozdíl od cenového zápisu nemá účtenka bez vazby na
   * majitele žádnou hodnotu: ceny z ní už dávno žijí ve vlastních observacích a zbytek je
   * přehled cizího nákupu.
   */
  @Modifying
  @Query("DELETE FROM Receipt r WHERE r.createdAt < :cutoff")
  int deleteOlderThan(@Param("cutoff") OffsetDateTime cutoff);
}
