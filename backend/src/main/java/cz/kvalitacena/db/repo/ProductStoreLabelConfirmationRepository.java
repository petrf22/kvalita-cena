package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ProductStoreLabelConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface ProductStoreLabelConfirmationRepository
    extends JpaRepository<ProductStoreLabelConfirmation, Long> {

  @Modifying
  @Query(value = """
      INSERT INTO core.product_store_label_confirmation(label_id, user_id)
      VALUES (:labelId, :userId)
      ON CONFLICT (label_id, user_id) WHERE user_id IS NOT NULL DO NOTHING
      """, nativeQuery = true)
  int insertIfAbsent(@Param("labelId") Long labelId, @Param("userId") Long userId);

  /** Vazba na účet se ruší po 180 dnech stejně jako u aliasu (docs/soukromi.md). */
  @Modifying
  @Query("UPDATE ProductStoreLabelConfirmation c SET c.userId = NULL "
      + "WHERE c.createdAt < :cutoff AND c.userId IS NOT NULL")
  int pseudonymizeBefore(@Param("cutoff") OffsetDateTime cutoff);
}
