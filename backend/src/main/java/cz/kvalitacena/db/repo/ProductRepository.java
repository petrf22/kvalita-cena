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
}
