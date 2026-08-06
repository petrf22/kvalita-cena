package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.RecordFlag;
import cz.kvalitacena.db.entity.RecordType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecordFlagRepository extends JpaRepository<RecordFlag, Long> {

  boolean existsByRecordTypeAndRecordIdAndUserId(RecordType recordType, Long recordId, Long userId);

  long countByRecordTypeAndRecordId(RecordType recordType, Long recordId);

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
}
