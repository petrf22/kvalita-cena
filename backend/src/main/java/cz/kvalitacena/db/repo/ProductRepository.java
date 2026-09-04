package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.Product;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, ProductSearchRepository {

  /** Detail starého sloučeného id musí umět přesměrovat na kanonický produkt. */
  @EntityGraph(attributePaths = {"mergedInto"})
  Optional<Product> findWithMergedIntoById(Long id);

  /** Dotažení pro hledání dávkou — vyhýbá se N+1 na brand/category u seznamu výsledků. */
  @EntityGraph(attributePaths = {"brand", "category"})
  List<Product> findWithBrandAndCategoryByIdIn(Collection<Long> ids);

  /**
   * Podobné zboží podle názvu (idx_product_name_trgm) — slouží jako první nabídka pro
   * bezkódový zápis (existující druhové položky nahoru) i jako povinná kontrola duplicit
   * před založením nového zboží (docs/reputace.md, "Zboží bez čárového kódu").
   */
  @Query(value = "SELECT p.* FROM core.product p "
      + "LEFT JOIN core.product_code pc ON pc.product_id=p.id AND pc.code_type='GTIN' "
      + "LEFT JOIN off.product op ON op.gtin=pc.code AND op.fetch_status='FOUND' "
      + "LEFT JOIN core.product_alias pa ON pa.product_id=p.id "
      + " AND (pa.status='ACTIVE' OR EXISTS (SELECT 1 FROM core.product_alias_confirmation pac "
      + " WHERE pac.alias_id=pa.id AND pac.user_id=:viewerId)) "
      + "WHERE p.status IN ('ACTIVE', 'DRAFT') "
      + "AND (:storeId IS NULL OR p.catalog_scope IN ('GLOBAL', 'LEGACY_GLOBAL') "
      + " OR (p.catalog_scope='STORE' AND p.scope_store_id=:storeId) "
      + " OR (p.catalog_scope='CHAIN' AND p.scope_chain_id=(SELECT s.chain_id FROM core.store s WHERE s.id=:storeId))) "
      + "AND (similarity(core.norm_text(COALESCE(op.product_name,p.name)), core.norm_text(:name)) > 0.2 "
      + " OR similarity(core.norm_text(pa.name), core.norm_text(:name)) > 0.2) "
      + "GROUP BY p.id, op.product_name "
      + "ORDER BY CASE WHEN p.scope_store_id=:storeId THEN 0 "
      + " WHEN p.scope_chain_id=(SELECT s.chain_id FROM core.store s WHERE s.id=:storeId) THEN 1 "
      + " WHEN p.catalog_scope='GLOBAL' THEN 2 ELSE 3 END, "
      + "p.is_generic DESC, GREATEST(similarity(core.norm_text(COALESCE(op.product_name,p.name)), core.norm_text(:name)), "
      + "COALESCE(MAX(similarity(core.norm_text(pa.name), core.norm_text(:name))),0)) DESC "
      + "LIMIT :limit", nativeQuery = true)
  List<Product> findSimilarByName(@Param("name") String name, @Param("storeId") Long storeId,
      @Param("viewerId") Long viewerId, @Param("limit") int limit);

  /** "Moje příspěvky" (MyContributionsService) — vlastní založené zboží, nejnovější první. */
  @Query(value = "SELECT * FROM core.product p WHERE p.created_by_user_id = :userId "
      + "ORDER BY p.created_at DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
  List<Product> findByCreatedByUserId(@Param("userId") Long userId, @Param("limit") int limit,
      @Param("offset") int offset);

  long countByCreatedByUserId(Long userId);
}
