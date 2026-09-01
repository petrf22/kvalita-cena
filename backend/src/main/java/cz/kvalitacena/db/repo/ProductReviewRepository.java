package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ProductReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductReviewRepository extends JpaRepository<ProductReview, Long> {

  Optional<ProductReview> findByProductIdAndUserId(Long productId, Long userId);

  /** GDPR export (AccountService) — všechna vlastní hodnocení bez ohledu na produkt. */
  List<ProductReview> findByUserId(Long userId);

  @Query("select r.productId as productId, avg(r.stars) as average, count(r) as count "
      + "from ProductReview r where r.productId in :productIds group by r.productId")
  List<QualityRow> summarize(@Param("productIds") Collection<Long> productIds);

  @Query("select r.productId as productId, r.stars as stars "
      + "from ProductReview r where r.userId = :userId and r.productId in :productIds")
  List<StarsRow> starsOfUser(@Param("userId") Long userId, @Param("productIds") Collection<Long> productIds);

  /**
   * Upsert jedním kolem — souběžná volání téhož uživatele na tentýž produkt nesmí spadnout na
   * {@code uq_product_review_user} (2026-08-05/01-product-quality-rating.yaml,
   * přejmenováno 2026-09-01/02-rename-product-review.yaml).
   */
  @Modifying
  @Query(value = "INSERT INTO core.product_review (product_id, user_id, stars) "
      + "VALUES (:productId, :userId, :stars) "
      + "ON CONFLICT (product_id, user_id) "
      + "DO UPDATE SET stars = EXCLUDED.stars, updated_at = CURRENT_TIMESTAMP",
      nativeQuery = true)
  void upsert(@Param("productId") Long productId, @Param("userId") Long userId, @Param("stars") short stars);

  interface QualityRow {
    Long getProductId();

    Double getAverage();

    long getCount();
  }

  interface StarsRow {
    Long getProductId();

    short getStars();
  }
}
