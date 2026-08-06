package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.Product;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long>, ProductSearchRepository {

  /**
   * Fulltext přes {@code idx_product_name_fts} (docs/datovy-model.md) — jednoduchá konfigurace
   * 'simple' bez českého slovníku, dost pro MVP.
   */
  @Query(value = "SELECT * FROM core.product p WHERE p.status = 'ACTIVE' "
      + "AND to_tsvector('simple', p.name) @@ plainto_tsquery('simple', :query) "
      + "LIMIT :limit", nativeQuery = true)
  List<Product> searchByName(@Param("query") String query, @Param("limit") int limit);

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
}
