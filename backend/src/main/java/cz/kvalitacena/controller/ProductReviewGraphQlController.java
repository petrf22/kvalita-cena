package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.service.ProductReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Třída se dřív jmenovala {@code QualityGraphQlController} — přejmenováno spolu s
 * {@code core.product_quality_rating} → {@code core.product_review}
 * (2026-09-01/02-rename-product-review.yaml).
 */
@Controller
@RequiredArgsConstructor
public class ProductReviewGraphQlController {

  private final ProductReviewService reviewService;

  @MutationMapping
  public ProductQuality rateProduct(@Argument Long productId, @Argument Integer stars, Authentication authentication) {
    return reviewService.rate(productId, stars, publicUidOf(authentication));
  }

  /**
   * Jediné pole závislé na uživateli. Spring GraphQL registruje DataLoadery PER REQUEST, takže
   * viewer je v rámci jedné dávky konstantní a klíč (productId) stačí — ale žádnou cache PŘES
   * request (Caffeine, @Cacheable) sem NIKDY nepřidávat, jinak by se data prolila mezi uživateli
   * (CLAUDE.md, "DataLoader musí mít viewera v cache klíči").
   */
  @BatchMapping(typeName = "Product", field = "myQualityRating")
  public Map<Product, Integer> myQualityRating(List<Product> products, Authentication authentication) {
    UUID publicUid = publicUidOf(authentication);
    Map<Long, Integer> stars = reviewService.starsOf(publicUid, products.stream().map(Product::getId).toList());
    Map<Product, Integer> result = new LinkedHashMap<>();
    for (Product p : products) {
      result.put(p, stars.get(p.getId()));
    }
    return result;
  }

  private UUID publicUidOf(Authentication authentication) {
    return authentication != null && authentication.getPrincipal() instanceof UUID uid ? uid : null;
  }
}
