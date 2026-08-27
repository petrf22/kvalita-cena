package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.Product;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long>, ProductSearchRepository {

  /** Dotažení pro hledání dávkou — vyhýbá se N+1 na brand/category u seznamu výsledků. */
  @EntityGraph(attributePaths = {"brand", "category"})
  List<Product> findWithBrandAndCategoryByIdIn(Collection<Long> ids);

  /**
   * Podobné zboží podle názvu (idx_product_name_trgm) — slouží jako první nabídka pro
   * bezkódový zápis (existující druhové položky nahoru) i jako povinná kontrola duplicit
   * před založením nového zboží (docs/reputace.md, "Zboží bez čárového kódu").
   */
  @Query(value = "SELECT * FROM core.product p WHERE p.status IN ('ACTIVE', 'DRAFT') "
      + "AND similarity(core.norm_text(p.name), core.norm_text(:name)) > 0.2 "
      + "ORDER BY p.is_generic DESC, similarity(core.norm_text(p.name), core.norm_text(:name)) DESC "
      + "LIMIT :limit", nativeQuery = true)
  List<Product> findSimilarByName(@Param("name") String name, @Param("limit") int limit);

  /** "Moje příspěvky" (MyContributionsService) — vlastní založené zboží, nejnovější první. */
  @Query(value = "SELECT * FROM core.product p WHERE p.created_by_user_id = :userId "
      + "ORDER BY p.created_at DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
  List<Product> findByCreatedByUserId(@Param("userId") Long userId, @Param("limit") int limit,
      @Param("offset") int offset);

  long countByCreatedByUserId(Long userId);
}
