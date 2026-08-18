package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.RecordFlag;
import cz.kvalitacena.db.entity.RecordType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface RecordFlagRepository extends JpaRepository<RecordFlag, Long> {

  boolean existsByRecordTypeAndRecordIdAndUserId(RecordType recordType, Long recordId, Long userId);

  /**
   * Jen NEVYŘÍZENÁ nahlášení — po moderátorském DISMISSED (ModerationService.resolveFlags)
   * se stará nahlášení nesmí počítat znovu do prahu, jinak by jediný nový hlas skryl odkrytý
   * záznam okamžitě zpátky, aniž by práh {@code app.moderation.flags-to-hide} skutečně dosáhly
   * NOVÁ nahlášení (docs/reputace.md, "Moderace").
   */
  long countByRecordTypeAndRecordIdAndResolvedAtIsNull(RecordType recordType, Long recordId);

  /**
   * {@code ON CONFLICT DO NOTHING} nad {@code uq_record_flag_user} — druhý hlas téhož člověka
   * na týž záznam nic nezmění (docs/reputace.md, jeden hlas na člověka a záznam).
   */
  @Query(value = "INSERT INTO core.record_flag (record_type, record_id, user_id, reason) "
      + "VALUES (:recordType, :recordId, :userId, :reason) "
      + "ON CONFLICT (record_type, record_id, user_id) DO NOTHING", nativeQuery = true)
  @Modifying
  void insertIgnoringDuplicate(@Param("recordType") String recordType, @Param("recordId") Long recordId,
      @Param("userId") Long userId, @Param("reason") String reason);

  /** Jeden řádek na (recordType, recordId) — fronta k přezkumu (ModerationService.flaggedRecords). */
  interface FlaggedGroup {
    String getRecordType();

    Long getRecordId();

    long getFlagCount();

    // Instant, ne OffsetDateTime — min()/max() nad TIMESTAMPTZ vrací z nativního dotazu jiný
    // JDBC typ než přímý sloupec, na který projekční proxy neumí OffsetDateTime namapovat
    // (UnsupportedOperationException). Převod na OffsetDateTime dělá volající (ModerationService).
    Instant getFirstFlaggedAt();

    Instant getLastFlaggedAt();

    /**
     * Důvody spojené neviditelným oddělovačem (chr(31), Unit Separator) — {@code array_agg} by
     * jako typ sloupce vrátil {@code text[]}, který nativní JPA projekce nemapuje spolehlivě.
     * Split provádí volající ({@code ModerationService}).
     */
    String getReasons();
  }

  @Query(value = "SELECT record_type AS recordType, record_id AS recordId, count(*) AS flagCount, "
      + "min(created_at) AS firstFlaggedAt, max(created_at) AS lastFlaggedAt, "
      + "string_agg(DISTINCT reason, chr(31)) FILTER (WHERE reason IS NOT NULL) AS reasons "
      + "FROM core.record_flag "
      + "WHERE resolved_at IS NULL AND (:recordType IS NULL OR record_type = :recordType) "
      + "GROUP BY record_type, record_id "
      // record_id jako tiebreaker — viz PriceObservationRepository.findForModeration, stejné
      // riziko nedeterministického stránkování při shodném max(created_at).
      + "ORDER BY max(created_at) DESC, record_id DESC "
      + "LIMIT :limit OFFSET :offset", nativeQuery = true)
  List<FlaggedGroup> findUnresolvedGroups(@Param("recordType") String recordType,
      @Param("limit") int limit, @Param("offset") int offset);

  @Query(value = "SELECT count(*) FROM (SELECT 1 FROM core.record_flag "
      + "WHERE resolved_at IS NULL AND (:recordType IS NULL OR record_type = :recordType) "
      + "GROUP BY record_type, record_id) t", nativeQuery = true)
  long countUnresolvedGroups(@Param("recordType") String recordType);

  /**
   * Vyřídí všechna dosud nevyřízená nahlášení jednoho záznamu najednou — druhé volání nad
   * stejným záznamem aktualizuje 0 řádků (idempotentní, viz ModerationService.resolveFlags).
   */
  @Transactional
  @Modifying
  @Query(value = "UPDATE core.record_flag SET resolved_at = now(), resolved_by_user_id = :moderatorId, "
      + "resolution = :resolution "
      + "WHERE record_type = :recordType AND record_id = :recordId AND resolved_at IS NULL",
      nativeQuery = true)
  int resolveAllPending(@Param("recordType") String recordType, @Param("recordId") Long recordId,
      @Param("moderatorId") Long moderatorId, @Param("resolution") String resolution);
}
