package cz.kvalitacena.db.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

/**
 * Hlasy „tohle zboží z účtenky je ve skutečnosti tamto zboží" ({@code core.product_merge_vote},
 * ReceiptItemRematchService). Bez entity — tabulka se jen zapisuje a počítá, nikdy se nenačítá
 * jako objekt.
 */
@Repository
@RequiredArgsConstructor
public class ProductMergeVoteRepository {

  private final JdbcTemplate jdbcTemplate;

  /** Jeden účet jeden hlas na zboží z účtenky — změna názoru hlas přepíše. */
  public void vote(Long sourceProductId, Long targetProductId, Long userId) {
    jdbcTemplate.update("""
        INSERT INTO core.product_merge_vote(source_product_id, target_product_id, user_id)
        VALUES (?, ?, ?)
        ON CONFLICT (source_product_id, user_id) WHERE user_id IS NOT NULL
        DO UPDATE SET target_product_id = EXCLUDED.target_product_id, created_at = CURRENT_TIMESTAMP
        """, sourceProductId, targetProductId, userId);
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
