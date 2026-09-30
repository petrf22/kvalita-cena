package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ReceiptLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface ReceiptLineRepository extends JpaRepository<ReceiptLine, Long> {

  List<ReceiptLine> findByReceiptIdOrderByLineNoAsc(Long receiptId);

  List<ReceiptLine> findByReceiptIdInOrderByLineNoAsc(Collection<Long> receiptIds);

  void deleteByReceiptId(Long receiptId);

  /**
   * Pozdější párování (docs/rozvoj.md, „Mapování obchodního označení"): nespárované řádky
   * jiných účtenek se stejným označením v rozsahu aktivovaného mapování. Jen účtenky mladší
   * než {@code notBefore} (pseudonymizační okno) — starší cenu už nemá smysl nabízet.
   * Účtenka bez potvrzeného obchodu se bere podle navrženého.
   */
  @Modifying
  @Query(value = """
      UPDATE core.receipt_line l SET status = 'SUGGESTED', product_id = :productId,
        match_source = 'LABEL', updated_at = CURRENT_TIMESTAMP
      FROM core.receipt r
      JOIN core.store s ON s.id = COALESCE(r.store_id, r.suggested_store_id)
      WHERE l.receipt_id = r.id AND l.status = 'UNMATCHED' AND l.label_norm = :labelNorm
        AND COALESCE(r.purchased_at, r.created_at) >= :notBefore
        AND ((CAST(:chainId AS BIGINT) IS NOT NULL AND s.chain_id = CAST(:chainId AS BIGINT))
          OR (CAST(:storeId AS BIGINT) IS NOT NULL AND s.id = CAST(:storeId AS BIGINT)))
      """, nativeQuery = true)
  int suggestUnmatched(@Param("labelNorm") String labelNorm, @Param("productId") Long productId,
      @Param("chainId") Long chainId, @Param("storeId") Long storeId,
      @Param("notBefore") OffsetDateTime notBefore);

  @Modifying
  @Query(value = "UPDATE core.receipt_line SET product_id = :targetId WHERE product_id = :sourceId",
      nativeQuery = true)
  int reassignProduct(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId);
}
