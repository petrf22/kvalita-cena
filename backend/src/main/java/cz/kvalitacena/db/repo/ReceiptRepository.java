package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.Receipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {

  Optional<Receipt> findByIdAndOwnerUserId(Long id, Long ownerUserId);

  @Query(value = "SELECT * FROM core.receipt WHERE owner_user_id = :ownerId "
      + "ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
  List<Receipt> findPageByOwner(@Param("ownerId") Long ownerId, @Param("limit") int limit,
      @Param("offset") int offset);

  List<Receipt> findByOwnerUserIdOrderByCreatedAtDesc(Long ownerUserId);

  long countByOwnerUserId(Long ownerUserId);

  long countByOwnerUserIdAndCreatedAtAfter(Long ownerUserId, OffsetDateTime after);

  /**
   * Účtenky, u kterých je pro vlastníka něco nového: zpracované a ještě neviděné, nebo s novým
   * návrhem spárování od jiných lidí (seen_at starší než poslední změna řádku). Zdroj
   * notifikace i odznaku (docs/soukromi.md, „Účtenky" — lokální notifikace, žádný push).
   */
  @Query(value = """
      SELECT count(*) FROM core.receipt r WHERE r.owner_user_id = :ownerId
        AND r.processed_at IS NOT NULL
        AND (r.seen_at IS NULL OR EXISTS (SELECT 1 FROM core.receipt_line l
             WHERE l.receipt_id = r.id AND l.status = 'SUGGESTED' AND l.updated_at > r.seen_at))
      """, nativeQuery = true)
  long countUnseen(@Param("ownerId") Long ownerId);

  /**
   * Obrázky ke smazání: zpracované před {@code cutoff}; se souhlasem s archivací až po
   * stažení do archivu, nejpozději po {@code archiveCutoff} (docs/soukromi.md, „Účtenky").
   * Nezpracované (worker dlouho neběžel) se nemažou — uživatel by přišel o účtenku dřív,
   * než ji kdo přečetl; pro ně platí {@code staleCutoff} od nahrání.
   */
  @Query(value = """
      SELECT * FROM core.receipt r WHERE r.images_deleted_at IS NULL AND (
        (r.processed_at IS NOT NULL AND r.processed_at < :cutoff
          AND (NOT r.archive_consent OR r.archived_at IS NOT NULL OR r.processed_at < :archiveCutoff))
        OR r.created_at < :staleCutoff)
      ORDER BY r.id LIMIT 200
      """, nativeQuery = true)
  List<Receipt> findImagesToDelete(@Param("cutoff") OffsetDateTime cutoff,
      @Param("archiveCutoff") OffsetDateTime archiveCutoff, @Param("staleCutoff") OffsetDateTime staleCutoff);

  @Query(value = """
      SELECT * FROM core.receipt r WHERE r.archive_consent AND r.archived_at IS NULL
        AND r.images_deleted_at IS NULL AND r.processed_at IS NOT NULL
      ORDER BY r.processed_at LIMIT :limit
      """, nativeQuery = true)
  List<Receipt> findArchiveCandidates(@Param("limit") int limit);

  Optional<Receipt> findByOwnerUserIdAndClientUploadId(Long ownerUserId, UUID clientUploadId);
}
