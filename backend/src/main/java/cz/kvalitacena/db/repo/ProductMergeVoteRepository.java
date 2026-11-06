package cz.kvalitacena.db.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Hlasy „tohle zboží z účtenky je ve skutečnosti tamto zboží" ({@code core.product_merge_vote},
 * ReceiptItemRematchService). Bez entity — tabulka se jen zapisuje a počítá, nikdy se nenačítá
 * jako objekt.
 */
@Repository
@RequiredArgsConstructor
public class ProductMergeVoteRepository {

  private final JdbcTemplate jdbcTemplate;

  /**
   * Jeden účet jeden hlas na zboží z účtenky — změna názoru hlas přepíše. Spolu s hlasem se
   * pamatuje, které vlastní ceny a řádky účtenek hlas přesunul na cíl a kterým cenám za balení
   * přepsal snapshot gramáží cíle: pro stejný cíl se přidávají, pro jiný nahradí (volající je
   * předtím vrátil na zdroj a přesunul znovu všechny).
   */
  public void vote(Long sourceProductId, Long targetProductId, Long userId, List<Long> movedObservationIds,
      List<Long> movedLineIds, List<Long> adoptedObservationIds) {
    jdbcTemplate.update("""
        INSERT INTO core.product_merge_vote(source_product_id, target_product_id, user_id,
                                            moved_observation_ids, moved_line_ids, adopted_observation_ids)
        VALUES (?, ?, ?, CAST(? AS BIGINT[]), CAST(? AS BIGINT[]), CAST(? AS BIGINT[]))
        ON CONFLICT (source_product_id, user_id) WHERE user_id IS NOT NULL
        DO UPDATE SET
          moved_observation_ids = CASE WHEN core.product_merge_vote.target_product_id = EXCLUDED.target_product_id
            THEN core.product_merge_vote.moved_observation_ids || EXCLUDED.moved_observation_ids
            ELSE EXCLUDED.moved_observation_ids END,
          moved_line_ids = CASE WHEN core.product_merge_vote.target_product_id = EXCLUDED.target_product_id
            THEN core.product_merge_vote.moved_line_ids || EXCLUDED.moved_line_ids
            ELSE EXCLUDED.moved_line_ids END,
          adopted_observation_ids = CASE WHEN core.product_merge_vote.target_product_id = EXCLUDED.target_product_id
            THEN core.product_merge_vote.adopted_observation_ids || EXCLUDED.adopted_observation_ids
            ELSE EXCLUDED.adopted_observation_ids END,
          target_product_id = EXCLUDED.target_product_id,
          created_at = CURRENT_TIMESTAMP
        """, sourceProductId, targetProductId, userId, pgArray(movedObservationIds), pgArray(movedLineIds),
            pgArray(adoptedObservationIds));
  }

  /** Cíl, pro který účet u zboží z účtenky naposledy hlasoval. */
  public Optional<Long> targetOf(Long sourceProductId, Long userId) {
    return jdbcTemplate.queryForList("""
        SELECT target_product_id FROM core.product_merge_vote WHERE source_product_id = ? AND user_id = ?
        """, Long.class, sourceProductId, userId).stream().findFirst();
  }

  /** Textový literál pole PostgreSQL ({@code {1,2,3}}) — JdbcTemplate bez Connection pole neumí. */
  private static String pgArray(List<Long> ids) {
    return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
  }

  /** Různí lidé, kteří hlasují pro stejný cíl. Hlas po pseudonymizaci (bez autora) se nepočítá. */
  public int countVoters(Long sourceProductId, Long targetProductId) {
    Integer votes = jdbcTemplate.queryForObject("""
        SELECT count(DISTINCT user_id) FROM core.product_merge_vote
        WHERE source_product_id = ? AND target_product_id = ? AND user_id IS NOT NULL
        """, Integer.class, sourceProductId, targetProductId);
    return votes == null ? 0 : votes;
  }

  public void deleteForSource(Long sourceProductId) {
    jdbcTemplate.update("DELETE FROM core.product_merge_vote WHERE source_product_id = ?", sourceProductId);
  }

  /** Pseudonymizace po 180 dnech (docs/soukromi.md) — hlas zůstane, autor zmizí. */
  public int pseudonymizeBefore(OffsetDateTime cutoff) {
    return jdbcTemplate.update(
        "UPDATE core.product_merge_vote SET user_id = NULL WHERE created_at < ? AND user_id IS NOT NULL", cutoff);
  }
}
