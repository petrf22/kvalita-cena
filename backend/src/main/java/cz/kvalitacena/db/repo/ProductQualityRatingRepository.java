package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ProductQualityRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductQualityRatingRepository extends JpaRepository<ProductQualityRating, Long> {

  Optional<ProductQualityRating> findByProductIdAndUserId(Long productId, Long userId);

  /** GDPR export (AccountService) — všechna vlastní hodnocení bez ohledu na produkt. */
  List<ProductQualityRating> findByUserId(Long userId);

  @Query("select r.productId as productId, avg(r.grade) as average, count(r) as count "
      + "from ProductQualityRating r where r.productId in :productIds group by r.productId")
  List<QualityRow> summarize(@Param("productIds") Collection<Long> productIds);

  @Query("select r.productId as productId, r.grade as grade "
      + "from ProductQualityRating r where r.userId = :userId and r.productId in :productIds")
  List<GradeRow> gradesOfUser(@Param("userId") Long userId, @Param("productIds") Collection<Long> productIds);

  /**
   * Upsert jedním kolem — souběžná volání téhož uživatele na tentýž produkt nesmí spadnout na
   * {@code uq_product_quality_rating_user} (2026-08-05/01-product-quality-rating.yaml).
   */
  @Modifying
  @Query(value = "INSERT INTO core.product_quality_rating (product_id, user_id, grade) "
      + "VALUES (:productId, :userId, :grade) "
      + "ON CONFLICT (product_id, user_id) "
      + "DO UPDATE SET grade = EXCLUDED.grade, updated_at = CURRENT_TIMESTAMP",
      nativeQuery = true)
  void upsert(@Param("productId") Long productId, @Param("userId") Long userId, @Param("grade") short grade);

  interface QualityRow {
    Long getProductId();

    Double getAverage();

    long getCount();
  }

  interface GradeRow {
    Long getProductId();

    short getGrade();
  }
}
