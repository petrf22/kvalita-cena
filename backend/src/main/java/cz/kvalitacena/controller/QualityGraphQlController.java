package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.service.QualityRatingService;
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

@Controller
@RequiredArgsConstructor
public class QualityGraphQlController {

  private final QualityRatingService qualityRatingService;

  @MutationMapping
  public ProductQuality rateProduct(@Argument Long productId, @Argument Integer grade, Authentication authentication) {
    return qualityRatingService.rate(productId, grade, publicUidOf(authentication));
  }

  /**
   * Jediné pole závislé na uživateli (docs/reputace.md — recenze etapy 2 to řeší ViewerContextem,
   * tady je ekvivalent nejmenší možný). Spring GraphQL registruje DataLoadery PER REQUEST, takže
   * viewer je v rámci jedné dávky konstantní a klíč (productId) stačí — ale žádnou cache PŘES
   * request (Caffeine, @Cacheable) sem NIKDY nepřidávat, jinak by se data prolila mezi uživateli
   * (CLAUDE.md, "DataLoader musí mít viewera v cache klíči").
   */
  @BatchMapping(typeName = "Product", field = "myQualityRating")
  public Map<Product, Integer> myQualityRating(List<Product> products, Authentication authentication) {
    UUID publicUid = publicUidOf(authentication);
    Map<Long, Integer> grades = qualityRatingService.gradesOf(publicUid, products.stream().map(Product::getId).toList());
    Map<Product, Integer> result = new LinkedHashMap<>();
    for (Product p : products) {
      result.put(p, grades.get(p.getId()));
    }
    return result;
  }

  private UUID publicUidOf(Authentication authentication) {
    return authentication != null && authentication.getPrincipal() instanceof UUID uid ? uid : null;
  }
}
