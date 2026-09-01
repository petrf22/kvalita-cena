package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.security.ViewerContext;
import cz.kvalitacena.security.ViewerContextResolver;
import cz.kvalitacena.service.ProductReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
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
  private final ViewerContextResolver viewerContextResolver;

  @MutationMapping
  public ProductQuality rateProduct(@Argument Long productId, @Argument Integer stars, Authentication authentication) {
    return reviewService.rate(productId, stars, publicUidOf(authentication));
  }

  @QueryMapping
  public ProductReviewResult productReviews(@Argument Long productId, @Argument Integer first,
      @Argument Integer offset, Authentication authentication) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    return reviewService.reviewsFor(productId, first, offset, viewer);
  }

  @MutationMapping
  public MyProductReview saveProductReviewText(@Argument Long productId, @Argument String text,
      Authentication authentication) {
    return reviewService.saveText(productId, text, publicUidOf(authentication));
  }

  @MutationMapping
  public MyProductReview deleteProductReviewText(@Argument Long productId, Authentication authentication) {
    return reviewService.deleteText(productId, publicUidOf(authentication));
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

  @BatchMapping(typeName = "Product", field = "reviewCount")
  public Map<Product, Integer> reviewCount(List<Product> products) {
    Map<Long, Integer> counts = reviewService.reviewCountsFor(products.stream().map(Product::getId).toList());
    Map<Product, Integer> result = new LinkedHashMap<>();
    for (Product p : products) {
      result.put(p, counts.getOrDefault(p.getId(), 0));
    }
    return result;
  }

  /** Stejné pravidlo jako myQualityRating výš — viewer musí být v klíči, DataLoader je jen per request. */
  @BatchMapping(typeName = "Product", field = "myReviewText")
  public Map<Product, String> myReviewText(List<Product> products, Authentication authentication) {
    UUID publicUid = publicUidOf(authentication);
    Map<Long, String> texts = reviewService.myReviewTextsOf(publicUid, products.stream().map(Product::getId).toList());
    Map<Product, String> result = new LinkedHashMap<>();
    for (Product p : products) {
      result.put(p, texts.get(p.getId()));
    }
    return result;
  }

  private UUID publicUidOf(Authentication authentication) {
    return authentication != null && authentication.getPrincipal() instanceof UUID uid ? uid : null;
  }
}
