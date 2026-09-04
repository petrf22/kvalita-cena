package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ProductAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductAliasRepository extends JpaRepository<ProductAlias, Long> {
  @Query(value = "SELECT * FROM core.product_alias WHERE product_id=:productId "
      + "AND core.norm_text(name)=core.norm_text(:name)", nativeQuery = true)
  Optional<ProductAlias> findByNormalizedName(@Param("productId") Long productId, @Param("name") String name);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(value = "UPDATE core.product_alias SET status='ACTIVE', activated_at=CURRENT_TIMESTAMP "
      + "WHERE id=:aliasId AND status='PENDING' AND "
      + "(SELECT count(DISTINCT user_id) FROM core.product_alias_confirmation "
      + " WHERE alias_id=:aliasId AND user_id IS NOT NULL) >= :threshold", nativeQuery = true)
  int activateIfConfirmed(@Param("aliasId") Long aliasId, @Param("threshold") int threshold);
}
