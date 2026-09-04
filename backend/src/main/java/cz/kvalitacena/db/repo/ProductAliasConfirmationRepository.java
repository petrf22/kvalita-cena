package cz.kvalitacena.db.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cz.kvalitacena.db.entity.ProductAliasConfirmation;

import java.time.OffsetDateTime;

public interface ProductAliasConfirmationRepository extends JpaRepository<ProductAliasConfirmation, Long> {
  @Modifying
  @Query(value = "INSERT INTO core.product_alias_confirmation(alias_id,user_id) VALUES (:aliasId,:userId) "
      + "ON CONFLICT (alias_id,user_id) WHERE user_id IS NOT NULL DO NOTHING", nativeQuery = true)
  int insertIfAbsent(@Param("aliasId") Long aliasId, @Param("userId") Long userId);

  long countByAliasIdAndUserIdIsNotNull(Long aliasId);

  @Modifying
  @Query("UPDATE ProductAliasConfirmation c SET c.userId=NULL "
      + "WHERE c.createdAt < :cutoff AND c.userId IS NOT NULL")
  int pseudonymizeBefore(@Param("cutoff") OffsetDateTime cutoff);
}
