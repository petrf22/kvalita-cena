package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.Receipt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {

  Optional<Receipt> findByIdAndOwnerUserId(Long id, Long ownerUserId);

  /**
   * Účtenka se zámkem — zápisy workeru (nové rozpoznání) a úpravy vlastníka se nad ní řadí za
   * sebe. Bez něj by zápis z dřív načteného snapshotu přepsal souběžnou úpravu hlavičky
   * (docs/ai.md, „Nové rozpoznání"). Musí to být PRVNÍ načtení účtenky v transakci — už
   * načtenou entitu Hibernate dotazem neobnoví, jen zamkne.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT r FROM Receipt r WHERE r.id = :id")
  Optional<Receipt> findForUpdateById(@Param("id") Long id);

  /** {@link #findForUpdateById} jen pro vlastníka. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT r FROM Receipt r WHERE r.id = :id AND r.ownerUserId = :ownerUserId")
  Optional<Receipt> findForUpdateByIdAndOwnerUserId(@Param("id") Long id, @Param("ownerUserId") Long ownerUserId);

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

  /**
   * Ostatní účtenky vlastníka, které ještě čekají na potvrzení obchodu — po potvrzení obchodu
   * u jedné z nich se jim přepočítá návrh (ReceiptStoreResuggester). Bez zámku: každá se pak
   * zamyká zvlášť ({@link #findForUpdateById}) a podmínky se ověří znovu.
   *
   * <p>I {@code PROCESSING}: worker mohl návrh spočítat před potvrzením a commitnout až po něm
   * — commitnutý stav je pak ještě PROCESSING a bez téhle podmínky by účtenka zůstala
   * s prázdným návrhem natrvalo. Zámek počká na commit workeru a přečte už PROCESSED.
   */
  @Query(value = """
      SELECT r.id FROM core.receipt r
      WHERE r.owner_user_id = :ownerId AND r.id <> :exceptId
        AND r.processing_status IN ('PROCESSING', 'PROCESSED') AND r.store_id IS NULL
        AND r.review_status <> 'DONE'
      ORDER BY r.id
      """, nativeQuery = true)
  List<Long> findOpenWithoutStoreIds(@Param("ownerId") Long ownerId, @Param("exceptId") Long exceptId);

  /**
   * Obchod, který vlastník už potvrdil u jiné své účtenky se stejnou hlavičkou — stejné IČO
   * (bez IČO stejný název) a stejná adresa, obojí po normalizaci. Pokryje i obchod pojmenovaný
   * jinak, než ho tiskne účtenka, který by textová shoda ({@code StoreRepository
   * .findReceiptCandidates}) nenašla. Jen vlastní účtenky — cizí potvrzení se nepoužívají.
   * Vyhrává poslední POTVRZENÍ ({@code store_confirmed_at}), ne poslední nahraná účtenka —
   * jinak by oprava obchodu u starší účtenky prohrála s chybnou volbou z novější.
   */
  @Query(value = """
      SELECT r.store_id FROM core.receipt r
      JOIN core.store s ON s.id = r.store_id
      WHERE r.owner_user_id = :ownerId AND r.id <> :receiptId
        AND s.status IN ('ACTIVE', 'PENDING') AND s.hidden_at IS NULL
        AND r.store_address_raw IS NOT NULL
        AND core.norm_text(r.store_address_raw) = core.norm_text(CAST(:address AS TEXT))
        AND ((CAST(:ico AS TEXT) IS NOT NULL
              AND regexp_replace(COALESCE(r.store_ico_raw, ''), '[^0-9]', '', 'g') = CAST(:ico AS TEXT))
          OR (CAST(:ico AS TEXT) IS NULL AND r.store_name_raw IS NOT NULL
              AND core.norm_text(r.store_name_raw) = core.norm_text(CAST(:name AS TEXT))))
      ORDER BY r.store_confirmed_at DESC, r.id DESC
      LIMIT 1
      """, nativeQuery = true)
  Optional<Long> findConfirmedStoreForHeader(@Param("ownerId") Long ownerId, @Param("receiptId") Long receiptId,
      @Param("ico") String ico, @Param("name") String name, @Param("address") String address);
}
