package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

  /**
   * Fronta pro moderátora ({@code FeedbackService.list}) — {@code handled} null vrací obojí,
   * jinak filtruje podle {@code handled_at IS NULL}/{@code IS NOT NULL}. {@code quarantined}
   * stejným způsobem filtruje {@code quarantined_at} — výchozí pohled ({@code false}) tak
   * podezřelé zprávy vůbec nevidí, "Podezřelé" záložka je {@code true}. Stejný vzor tvaru
   * dotazu jako {@code ProductRepository.findByCreatedByUserId}.
   */
  @Query(value = "SELECT * FROM core.feedback f WHERE (CAST(:handled AS boolean) IS NULL "
      + "OR (:handled = true AND f.handled_at IS NOT NULL) OR (:handled = false AND f.handled_at IS NULL)) "
      + "AND (:quarantined = true AND f.quarantined_at IS NOT NULL "
      + "OR :quarantined = false AND f.quarantined_at IS NULL) "
      + "ORDER BY f.created_at DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
  List<Feedback> findPage(@Param("handled") Boolean handled, @Param("quarantined") boolean quarantined,
      @Param("limit") int limit, @Param("offset") int offset);

  @Query(value = "SELECT count(*) FROM core.feedback f WHERE (CAST(:handled AS boolean) IS NULL "
      + "OR (:handled = true AND f.handled_at IS NOT NULL) OR (:handled = false AND f.handled_at IS NULL)) "
      + "AND (:quarantined = true AND f.quarantined_at IS NOT NULL "
      + "OR :quarantined = false AND f.quarantined_at IS NULL)",
      nativeQuery = true)
  long countPage(@Param("handled") Boolean handled, @Param("quarantined") boolean quarantined);

  /**
   * Dedup opakovaného spamu ({@code FeedbackSpamDetector}) — stejná zpráva odeslaná za poslední
   * den je silný signál, i bez shody IP (tu appka nikdy neukládá, viz {@code message_hash}
   * na entitě).
   */
  boolean existsByMessageHashAndCreatedAtAfter(byte[] messageHash, OffsetDateTime after);
}
