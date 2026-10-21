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

  /**
   * Pseudonymizace (docs/soukromi.md, „Účtenky"): řádek účtenky drží vlastníka po celý život
   * účtu, takže vazba na observaci starší než {@code cutoff} by obnovila vazbu cena → autor.
   * Bez změny {@code updated_at} — účtenka se kvůli tomu nesmí rozsvítit jako novinka.
   */
  @Modifying
  @Query(value = """
      UPDATE core.receipt_line l SET observation_id = NULL
      FROM core.price_observation o
      WHERE o.id = l.observation_id AND o.observed_at < :cutoff
      """, nativeQuery = true)
  int pseudonymizeObservationLinksBefore(@Param("cutoff") OffsetDateTime cutoff);
}
