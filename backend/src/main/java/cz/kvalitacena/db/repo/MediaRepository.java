package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.Media;
import cz.kvalitacena.db.entity.RecordType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MediaRepository extends JpaRepository<Media, Long> {

  List<Media> findByRecordTypeAndRecordIdOrderBySortOrderAscIdAsc(RecordType recordType, Long recordId);

  /** Dotažení fotek pro víc záznamů najednou (detail výsledků hledání) — vyhýbá se N+1. */
  List<Media> findByRecordTypeAndRecordIdInOrderBySortOrderAscIdAsc(RecordType recordType, Collection<Long> recordIds);

  long countByRecordTypeAndRecordId(RecordType recordType, Long recordId);

  /** Idempotence uploadu — druhé nahrání téhož obsahu ke stejnému záznamu vrátí existující řádek. */
  Optional<Media> findByRecordTypeAndRecordIdAndSha256(RecordType recordType, Long recordId, byte[] sha256);

  /**
   * Výmaz účtu (AccountService, docs/soukromi.md) — {@code fk_media_uploaded_by} je
   * {@code ON DELETE CASCADE}, takže smazání {@code app_user} řádku smaže i tyhle řádky v DB,
   * ale NE soubory na disku. Volající proto musí soubory smazat přes {@code MediaStorage}
   * explicitně PŘED smazáním uživatele, jinak vzniknou sirotčí soubory.
   */
  List<Media> findByUploadedByUserId(Long uploadedByUserId);
}
