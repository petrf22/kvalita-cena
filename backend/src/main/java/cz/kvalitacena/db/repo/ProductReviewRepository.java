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

  /** GDPR export (AccountService) — všechna vlastní hodnocení bez ohledu na produkt, bez stránkování. */
  List<ProductReview> findByUserId(Long userId);

  /**
   * Recenze pod zbožím — jen s textem a neskryté, nejnovější první
   * (idx_product_review_listing, 2026-09-01/03-product-review-text.yaml).
   */
  @Query(value = "SELECT * FROM core.product_review WHERE product_id = :productId "
      + "AND text IS NOT NULL AND hidden_at IS NULL "
      + "ORDER BY created_at DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
  List<ProductReview> findVisibleTextsByProduct(@Param("productId") Long productId,
      @Param("limit") int limit, @Param("offset") int offset);

  @Query("select count(r) from ProductReview r "
      + "where r.productId = :productId and r.text is not null and r.hiddenAt is null")
  long countVisibleTextsByProduct(@Param("productId") Long productId);

  /** Product.reviewCount v dávce (@BatchMapping) — stejný filtr jako {@link #countVisibleTextsByProduct}. */
  @Query("select r.productId as productId, count(r) as count from ProductReview r "
      + "where r.productId in :productIds and r.text is not null and r.hiddenAt is null "
      + "group by r.productId")
  List<ReviewCountRow> countVisibleTextsByProducts(@Param("productIds") Collection<Long> productIds);

  /** "Moje recenze" (MyContributionsService) — jen vlastní, s textem, i skryté (autor vidí proč zmizely). */
  @Query(value = "SELECT * FROM core.product_review WHERE user_id = :userId AND text IS NOT NULL "
      + "ORDER BY created_at DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
  List<ProductReview> findTextsByUser(@Param("userId") Long userId, @Param("limit") int limit,
      @Param("offset") int offset);

  long countByUserIdAndTextIsNotNull(Long userId);

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

  interface ReviewCountRow {
    Long getProductId();

    long getCount();
  }
}
