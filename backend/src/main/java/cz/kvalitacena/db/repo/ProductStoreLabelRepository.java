package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ProductStoreLabel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ProductStoreLabelRepository extends JpaRepository<ProductStoreLabel, Long> {

  /**
   * Přesná shoda označení (krok 2 kaskády, docs/rozvoj.md) — nejdřív provozovna, pak řetězec.
   * PENDING mapování platí jen pro toho, kdo ho sám potvrdil (vzor aliasu).
   */
  @Query(value = """
      SELECT l.* FROM core.product_store_label l
      JOIN core.product p ON p.id = l.product_id AND p.status IN ('ACTIVE', 'DRAFT')
      WHERE l.label_norm = core.norm_label(:label)
        AND (l.store_id = :storeId OR (CAST(:chainId AS BIGINT) IS NOT NULL AND l.chain_id = CAST(:chainId AS BIGINT)))
        AND (l.status = 'ACTIVE' OR EXISTS (SELECT 1 FROM core.product_store_label_confirmation c
             WHERE c.label_id = l.id AND c.user_id = :viewerId))
      ORDER BY CASE WHEN l.store_id IS NOT NULL THEN 0 ELSE 1 END
      LIMIT 1
      """, nativeQuery = true)
  List<ProductStoreLabel> findExact(@Param("label") String label, @Param("storeId") Long storeId,
      @Param("chainId") Long chainId, @Param("viewerId") Long viewerId);

  /** Podobná označení v rozsahu — první část našeptávače u nespárovaného řádku. */
  @Query(value = """
      SELECT l.* FROM core.product_store_label l
      JOIN core.product p ON p.id = l.product_id AND p.status IN ('ACTIVE', 'DRAFT')
      WHERE (l.store_id = :storeId OR (CAST(:chainId AS BIGINT) IS NOT NULL AND l.chain_id = CAST(:chainId AS BIGINT)))
        AND similarity(l.label_norm, core.norm_label(:label)) > :threshold
      ORDER BY similarity(l.label_norm, core.norm_label(:label)) DESC
      LIMIT :limit
      """, nativeQuery = true)
  List<ProductStoreLabel> findSimilar(@Param("label") String label, @Param("storeId") Long storeId,
      @Param("chainId") Long chainId, @Param("threshold") double threshold, @Param("limit") int limit);

  List<ProductStoreLabel> findByProductIdInOrderByLastSeenAtDesc(Collection<Long> productIds);

  @Modifying(flushAutomatically = true)
  @Query(value = "UPDATE core.product_store_label SET status='ACTIVE', activated_at=CURRENT_TIMESTAMP "
      + "WHERE id=:labelId AND status='PENDING' AND "
      + "(SELECT count(DISTINCT user_id) FROM core.product_store_label_confirmation "
      + " WHERE label_id=:labelId AND user_id IS NOT NULL) >= :threshold", nativeQuery = true)
  int activateIfConfirmed(@Param("labelId") Long labelId, @Param("threshold") int threshold);

  @Modifying
  @Query(value = "INSERT INTO core.product_store_label_confirmation(label_id,user_id) VALUES (:labelId,:userId) "
      + "ON CONFLICT (label_id,user_id) WHERE user_id IS NOT NULL DO NOTHING", nativeQuery = true)
  int confirm(@Param("labelId") Long labelId, @Param("userId") Long userId);

  @Modifying
  @Query(value = "UPDATE core.product_store_label_confirmation SET user_id = NULL "
      + "WHERE created_at < :cutoff AND user_id IS NOT NULL", nativeQuery = true)
  int pseudonymizeConfirmationsBefore(@Param("cutoff") java.time.OffsetDateTime cutoff);
}
