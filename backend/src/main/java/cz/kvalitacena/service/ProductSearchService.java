package cz.kvalitacena.service;

import cz.kvalitacena.controller.ConvertedPrice;
import cz.kvalitacena.controller.ProductSearchItem;
import cz.kvalitacena.controller.ProductSearchResult;
import cz.kvalitacena.controller.SearchFacets;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductSearchCriteria;
import cz.kvalitacena.db.repo.ProductSearchRow;
import cz.kvalitacena.db.repo.ProductSort;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.service.fx.FxRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hledání s filtrem obchod/město a řazením — viz {@link cz.kvalitacena.db.repo.ProductSearchRepositoryImpl}
 * pro samotný SQL dotaz. Tady jen ořez limitů a doplnění Product/Store objektů dvěma dávkovými
 * dotazy, žádný N+1.
 */
@Service
@RequiredArgsConstructor
public class ProductSearchService {

  private static final int MAX_FIRST = 50;
  private static final int MAX_OFFSET = 500;

  private final ProductRepository productRepository;
  private final StoreRepository storeRepository;
  private final ProductOverlayService productOverlayService;
  private final FxRateService fxRateService;

  @Transactional(readOnly = true)
  public ProductSearchResult search(String query, Long storeId, String city, String country, ProductSort sort,
      Integer first, Integer offset, Long viewerId, String displayCurrency) {
    int limitedFirst = clamp(first == null ? 20 : first, 1, MAX_FIRST);
    int limitedOffset = clamp(offset == null ? 0 : offset, 0, MAX_OFFSET);
    ProductSearchCriteria criteria = new ProductSearchCriteria(
        query, storeId, city, country, sort == null ? ProductSort.REPORT_COUNT : sort, limitedFirst, limitedOffset,
        viewerId);

    List<ProductSearchRow> rows = productRepository.search(criteria);
    if (rows.isEmpty()) {
      long totalCount = productRepository.count(criteria);
      return new ProductSearchResult(List.of(), (int) totalCount, false);
    }

    List<Long> productIds = rows.stream().map(ProductSearchRow::productId).toList();
    List<Product> products = productOverlayService.applyOverlay(
        productRepository.findWithBrandAndCategoryByIdIn(productIds), viewerId);
    Map<Long, Product> productsById = products.stream()
        .collect(Collectors.toMap(Product::getId, Function.identity()));

    Set<Long> storeIds = rows.stream()
        .map(ProductSearchRow::cheapestStoreId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    Map<Long, Store> storesById = storeIds.isEmpty()
        ? Map.of()
        : storeRepository.findAllById(storeIds).stream().collect(Collectors.toMap(Store::getId, Function.identity()));

    List<ProductSearchItem> items = rows.stream()
        .filter(row -> productsById.containsKey(row.productId())) // produkt mezitím zmizel/smazán
        .map(row -> toItem(row, productsById, storesById, displayCurrency))
        .toList();

    long totalCount = productRepository.count(criteria);
    boolean hasMore = (long) limitedOffset + items.size() < totalCount;
    return new ProductSearchResult(items, (int) totalCount, hasMore);
  }

  @Transactional(readOnly = true)
  public SearchFacets facets(String country) {
    return new SearchFacets(storeRepository.findDistinctWithPrices(country),
        storeRepository.findDistinctCitiesWithPrices(country));
  }

  private ProductSearchItem toItem(ProductSearchRow row, Map<Long, Product> productsById, Map<Long, Store> storesById,
      String displayCurrency) {
    // Kurz k lastObservedAt, ne dnešní (docs/lokalizace.md) — stejné pravidlo jako ProductStats.
    LocalDate rateAt = row.lastObservedAt() == null ? null
        : row.lastObservedAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    ConvertedPrice converted = displayCurrency == null || rateAt == null ? null
        : ConvertedPrice.from(fxRateService.convert(row.bestPrice(), row.currency(), displayCurrency, rateAt));
    ConvertedPrice convertedUnit = displayCurrency == null || rateAt == null ? null
        : ConvertedPrice.from(fxRateService.convert(row.bestUnitPrice(), row.currency(), displayCurrency, rateAt));
    return new ProductSearchItem(
        productsById.get(row.productId()),
        (int) row.observationCount(),
        row.bestPrice(),
        row.bestUnitPrice(),
        row.cheapestStoreId() == null ? null : storesById.get(row.cheapestStoreId()),
        row.bestPriceObservations(),
        row.lastObservedAt(),
        row.qualityAverage(),
        (int) row.qualityCount(),
        row.currency(),
        converted,
        convertedUnit);
  }

  private int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
