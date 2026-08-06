package cz.kvalitacena.controller;

import cz.kvalitacena.config.ExternalLinkProperties;
import cz.kvalitacena.db.entity.CodeType;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.PriceCurrent;
import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.ProductCode;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.PriceCurrentRepository;
import cz.kvalitacena.db.repo.ProductCodeRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductSort;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.service.ProductSearchService;
import cz.kvalitacena.service.QualityRatingService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class ProductGraphQlController {

  private static final int GTIN_14_LENGTH = 14;

  private final ProductRepository productRepository;
  private final PriceCurrentRepository priceCurrentRepository;
  private final ProductCodeRepository productCodeRepository;
  private final StoreRepository storeRepository;
  private final ProductSearchService productSearchService;
  private final QualityRatingService qualityRatingService;
  private final ExternalLinkProperties externalLinkProperties;

  @QueryMapping
  public ProductSearchResult searchProducts(@Argument String query, @Argument Long storeId,
      @Argument String city, @Argument ProductSort sort, @Argument Integer first, @Argument Integer offset) {
    return productSearchService.search(query, storeId, city, sort, first, offset);
  }

  @QueryMapping
  public SearchFacets searchFacets(@Argument String query) {
    return productSearchService.facets();
  }

  @QueryMapping
  public Product product(@Argument Long id) {
    return productRepository.findById(id).orElse(null);
  }

  @QueryMapping
  public Product productByCode(@Argument String code) {
    return productCodeRepository.findFirstByCodeAndCodeType(normalizeToGtin14(code), CodeType.GTIN)
        .map(ProductCode::getProduct)
        .orElse(null);
  }

  /** EAN-8/EAN-13 doplněný nulami zleva na GTIN-14 — viz docs/datovy-model.md. */
  private String normalizeToGtin14(String code) {
    String digits = code.trim();
    if (digits.length() >= GTIN_14_LENGTH) return digits;
    return "0".repeat(GTIN_14_LENGTH - digits.length()) + digits;
  }

  /** Opak normalizace — GTIN-14 zpět na EAN bez vedoucích nul, jak ho zná Open Food Facts. */
  private String stripLeadingZeros(String gtin) {
    String stripped = gtin.replaceFirst("^0+(?!$)", "");
    return stripped.isEmpty() ? gtin : stripped;
  }

  @BatchMapping(typeName = "Product", field = "prices")
  public Map<Product, List<PriceCurrent>> prices(List<Product> products) {
    Map<Long, List<PriceCurrent>> byProduct = pricesByProductId(products);
    Map<Product, List<PriceCurrent>> result = new LinkedHashMap<>();
    for (Product p : products) {
      result.put(p, byProduct.getOrDefault(p.getId(), List.of()));
    }
    return result;
  }

  @BatchMapping(typeName = "Product", field = "codes")
  public Map<Product, List<ProductCode>> codes(List<Product> products) {
    Map<Long, List<ProductCode>> byProduct = codesByProductId(products);
    Map<Product, List<ProductCode>> result = new LinkedHashMap<>();
    for (Product p : products) {
      result.put(p, byProduct.getOrDefault(p.getId(), List.of()));
    }
    return result;
  }

  @BatchMapping(typeName = "Product", field = "gtin")
  public Map<Product, String> gtin(List<Product> products) {
    Map<Long, List<ProductCode>> byProduct = codesByProductId(products);
    Map<Product, String> result = new LinkedHashMap<>();
    for (Product p : products) {
      result.put(p, primaryGtin(byProduct.getOrDefault(p.getId(), List.of())));
    }
    return result;
  }

  @BatchMapping(typeName = "Product", field = "stats")
  public Map<Product, ProductStats> stats(List<Product> products) {
    Map<Long, List<PriceCurrent>> byProduct = pricesByProductId(products);

    record Raw(int observationCount, int storeCount, OffsetDateTime lastObservedAt,
               BigDecimal bestPrice, BigDecimal bestUnitPrice, Long cheapestStoreId) {
    }

    Map<Long, Raw> rawByProduct = new HashMap<>();
    for (Product p : products) {
      List<PriceCurrent> prices = byProduct.getOrDefault(p.getId(), List.of());
      int observationCount = prices.stream().mapToInt(PriceCurrent::getNObs).sum();
      int storeCount = (int) prices.stream().map(PriceCurrent::getStoreId).distinct().count();
      OffsetDateTime lastObservedAt = prices.stream()
          .map(PriceCurrent::getLastObservedAt)
          .filter(Objects::nonNull)
          .max(Comparator.naturalOrder())
          .orElse(null);
      // Jen REGULAR — PROMO by vždy vyhrálo (docs/datovy-model.md, price_kind se nemíchá).
      PriceCurrent cheapest = prices.stream()
          .filter(pc -> pc.getPriceKind() == PriceKind.REGULAR && pc.getUnitPrice() != null)
          .min(Comparator.comparing(PriceCurrent::getUnitPrice))
          .orElse(null);
      rawByProduct.put(p.getId(), new Raw(observationCount, storeCount, lastObservedAt,
          cheapest == null ? null : cheapest.getPriceAmount(),
          cheapest == null ? null : cheapest.getUnitPrice(),
          cheapest == null ? null : cheapest.getStoreId()));
    }

    Set<Long> storeIds = rawByProduct.values().stream()
        .map(Raw::cheapestStoreId).filter(Objects::nonNull).collect(Collectors.toSet());
    Map<Long, Store> storesById = storeIds.isEmpty()
        ? Map.of()
        : storeRepository.findAllById(storeIds).stream().collect(Collectors.toMap(Store::getId, Function.identity()));

    Map<Product, ProductStats> result = new LinkedHashMap<>();
    for (Product p : products) {
      Raw raw = rawByProduct.get(p.getId());
      Store cheapestStore = raw.cheapestStoreId() == null ? null : storesById.get(raw.cheapestStoreId());
      result.put(p, new ProductStats(raw.observationCount(), raw.storeCount(), raw.lastObservedAt(),
          raw.bestPrice(), raw.bestUnitPrice(), cheapestStore));
    }
    return result;
  }

  @BatchMapping(typeName = "Product", field = "quality")
  public Map<Product, ProductQuality> quality(List<Product> products) {
    Map<Long, ProductQuality> summaries = qualityRatingService.summariesFor(productIds(products));
    Map<Product, ProductQuality> result = new LinkedHashMap<>();
    for (Product p : products) {
      result.put(p, summaries.getOrDefault(p.getId(), ProductQuality.EMPTY));
    }
    return result;
  }

  @BatchMapping(typeName = "Product", field = "externalLinks")
  public Map<Product, List<ExternalLink>> externalLinks(List<Product> products) {
    Map<Long, List<ProductCode>> byProduct = codesByProductId(products);
    Map<Product, List<ExternalLink>> result = new LinkedHashMap<>();
    for (Product p : products) {
      String gtin = primaryGtin(byProduct.getOrDefault(p.getId(), List.of()));
      result.put(p, externalLinksFor(gtin));
    }
    return result;
  }

  private List<ExternalLink> externalLinksFor(String gtin) {
    if (gtin == null) return List.of();
    List<ExternalLink> links = new ArrayList<>();
    String ean = stripLeadingZeros(gtin);
    ExternalLinkProperties.OpenFoodFacts off = externalLinkProperties.getOpenFoodFacts();
    links.add(new ExternalLink(
        ExternalLinkKind.OPEN_FOOD_FACTS,
        "Open Food Facts",
        off.getProductUrlTemplate().replace("{barcode}", ean),
        off.getAttribution()));
    return links;
  }

  private String primaryGtin(List<ProductCode> codes) {
    return codes.stream()
        .filter(c -> c.getCodeType() == CodeType.GTIN)
        .filter(ProductCode::isPrimary)
        .findFirst()
        .or(() -> codes.stream().filter(c -> c.getCodeType() == CodeType.GTIN).findFirst())
        .map(ProductCode::getCode)
        .orElse(null);
  }

  private List<Long> productIds(List<Product> products) {
    return products.stream().map(Product::getId).toList();
  }

  private Map<Long, List<PriceCurrent>> pricesByProductId(List<Product> products) {
    return priceCurrentRepository.findByProductIdIn(productIds(products)).stream()
        .collect(Collectors.groupingBy(PriceCurrent::getProductId));
  }

  private Map<Long, List<ProductCode>> codesByProductId(List<Product> products) {
    return productCodeRepository.findByProductIdIn(productIds(products)).stream()
        .collect(Collectors.groupingBy(pc -> pc.getProduct().getId()));
  }
}
