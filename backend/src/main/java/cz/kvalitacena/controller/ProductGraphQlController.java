package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.CodeType;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.PriceCurrent;
import cz.kvalitacena.db.repo.PriceCurrentRepository;
import cz.kvalitacena.db.repo.ProductCodeRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class ProductGraphQlController {

  private static final int MAX_SEARCH_RESULTS = 50;
  private static final int GTIN_14_LENGTH = 14;

  private final ProductRepository productRepository;
  private final PriceCurrentRepository priceCurrentRepository;
  private final ProductCodeRepository productCodeRepository;

  @QueryMapping
  public List<Product> searchProducts(@Argument String query, @Argument Integer first) {
    int limit = Math.min(first == null ? 20 : first, MAX_SEARCH_RESULTS);
    return productRepository.searchByName(query, limit);
  }

  @QueryMapping
  public Product product(@Argument Long id) {
    return productRepository.findById(id).orElse(null);
  }

  @QueryMapping
  public Product productByCode(@Argument String code) {
    return productCodeRepository.findFirstByCodeAndCodeType(normalizeToGtin14(code), CodeType.GTIN)
        .map(pc -> pc.getProduct())
        .orElse(null);
  }

  /** EAN-8/EAN-13 doplněný nulami zleva na GTIN-14 — viz docs/datovy-model.md. */
  private String normalizeToGtin14(String code) {
    String digits = code.trim();
    if (digits.length() >= GTIN_14_LENGTH) return digits;
    return "0".repeat(GTIN_14_LENGTH - digits.length()) + digits;
  }

  @SchemaMapping(typeName = "Product", field = "prices")
  public List<PriceCurrent> prices(Product product) {
    return priceCurrentRepository.findByProductId(product.getId());
  }
}
