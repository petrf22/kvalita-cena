package cz.kvalitacena.controller;

import cz.kvalitacena.config.ExternalLinkProperties;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.CategoryI18n;
import cz.kvalitacena.db.entity.CodeType;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.PriceCurrent;
import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.ProductCode;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.entity.RecordType;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.CategoryI18nRepository;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.PriceCurrentRepository;
import cz.kvalitacena.db.repo.ProductCodeRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductSort;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.security.ViewerContext;
import cz.kvalitacena.security.ViewerContextResolver;
import cz.kvalitacena.service.CatalogEditService;
import cz.kvalitacena.service.CountryResolver;
import cz.kvalitacena.service.GtinNormalization;
import cz.kvalitacena.service.MediaService;
import cz.kvalitacena.service.MyPriceService;
import cz.kvalitacena.service.OffLookupResult;
import cz.kvalitacena.service.OffLookupStatus;
import cz.kvalitacena.service.OffNetContent;
import cz.kvalitacena.service.OffNetContentConverter;
import cz.kvalitacena.service.OffProductCatalogService;
import cz.kvalitacena.service.OpenFoodFactsService;
import cz.kvalitacena.service.ProductCatalogService;
import cz.kvalitacena.service.Messages;
import cz.kvalitacena.service.ProductOverlayService;
import cz.kvalitacena.service.ProductSearchService;
import cz.kvalitacena.service.QualityRatingService;
import cz.kvalitacena.service.fx.FxRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.text.Normalizer;
import java.util.function.Function;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class ProductGraphQlController {

  private static final int MAX_SUGGESTIONS = 30;

  private final ProductRepository productRepository;
  private final PriceCurrentRepository priceCurrentRepository;
  private final ProductCodeRepository productCodeRepository;
  private final StoreRepository storeRepository;
  private final CategoryRepository categoryRepository;
  private final CategoryI18nRepository categoryI18nRepository;
  private final ProductSearchService productSearchService;
  private final QualityRatingService qualityRatingService;
  private final ProductCatalogService productCatalogService;
  private final OffProductCatalogService offProductCatalogService;
  private final OpenFoodFactsService openFoodFactsService;
  private final OffNetContentConverter offNetContentConverter;
  private final ProductOverlayService productOverlayService;
  private final CatalogEditService catalogEditService;
  private final MyPriceService myPriceService;
  private final MediaService mediaService;
  private final ViewerContextResolver viewerContextResolver;
  private final ExternalLinkProperties externalLinkProperties;
  private final Messages messages;
  private final CountryResolver countryResolver;
  private final FxRateService fxRateService;

  /**
   * country bez zadání NEZNAMENÁ "celý svět" (docs/lokalizace.md) — bez filtru by
   * ProductSort.PRICE_ASC řadilo CZK vedle PLN v jednom sloupci.
   */
  @QueryMapping
  public ProductSearchResult searchProducts(@Argument String query, @Argument Long storeId,
      @Argument String city, @Argument Long categoryId, @Argument String country, @Argument ProductSort sort,
      @Argument Integer first, @Argument Integer offset, Authentication authentication,
      @ContextValue(name = "displayCurrency", required = false) String displayCurrency) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    // Týž zdroj jako @BatchMapping categoryName níže — jinak by se zboží mohlo najít pod
    // názvem kategorie, který v odpovědi vůbec nesvítí.
    String locale = LocaleContextHolder.getLocale().getLanguage();
    return productSearchService.search(query, storeId, city, categoryId,
        countryResolver.resolve(country, viewer.userId()), sort, first, offset, viewer.userId(), displayCurrency,
        locale);
  }

  @QueryMapping
  public SearchFacets searchFacets(@Argument String query, @Argument String country, Authentication authentication) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    return productSearchService.facets(countryResolver.resolve(country, viewer.userId()));
  }

  @QueryMapping
  public Product product(@Argument Long id, Authentication authentication) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    return productRepository.findById(id)
        .filter(p -> isVisible(p, viewer))
        .map(p -> productOverlayService.applyOverlay(p, viewer.userId()))
        .orElse(null);
  }

  @QueryMapping
  public Product productByCode(@Argument String code, Authentication authentication) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    return productCodeRepository.findFirstByCodeAndCodeType(GtinNormalization.toGtin14(code), CodeType.GTIN)
        .map(ProductCode::getProduct)
        .filter(p -> isVisible(p, viewer))
        .map(p -> productOverlayService.applyOverlay(p, viewer.userId()))
        .orElse(null);
  }

  @QueryMapping
  public ProductLookupResult productLookupByCode(@Argument String code, Authentication authentication) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    String gtin;
    try {
      if (code == null || !code.trim().matches("[0-9]{8,14}")) {
        return new ProductLookupResult(ProductLookupStatus.NOT_FOUND, null, null);
      }
      gtin = GtinNormalization.toGtin14(code);
    } catch (RuntimeException e) {
      return new ProductLookupResult(ProductLookupStatus.NOT_FOUND, null, null);
    }
    Product existing = productCodeRepository.findFirstByCodeAndCodeType(gtin, CodeType.GTIN)
        .map(ProductCode::getProduct).filter(p -> isVisible(p, viewer))
        .map(p -> productOverlayService.applyOverlay(p, viewer.userId())).orElse(null);
    if (existing != null) return new ProductLookupResult(ProductLookupStatus.EXISTING, existing, null);

    OffLookupResult lookup = openFoodFactsService.lookup(code);
    if (lookup.status() == OffLookupStatus.NOT_FOUND) {
      return new ProductLookupResult(ProductLookupStatus.NOT_FOUND, null, null);
    }
    if (lookup.status() == OffLookupStatus.UNAVAILABLE) {
      return new ProductLookupResult(ProductLookupStatus.OFF_UNAVAILABLE, null, null);
    }
    var off = lookup.product();
    Category category = off.getMappedCategorySlug() == null ? null
        : categoryRepository.findBySlug(off.getMappedCategorySlug()).orElse(null);
    OffNetContent content = offNetContentConverter.convert(off);
    String attribution = messages.get("attribution.off");
    ExternalProductImage image = externalImage(off.getImageFrontUrl(), off.getImageFrontSmallUrl(), attribution);
    ExternalProductCandidate candidate = new ExternalProductCandidate(
        stripLeadingZeros(off.getGtin()), off.getProductName(), off.getBrandName(), category,
        content == null ? null : content.unitBase(), content == null ? null : content.value(),
        content == null ? null : content.uom(), image,
        externalLinkProperties.getOpenFoodFacts().getProductUrlTemplate()
            .replace("{barcode}", stripLeadingZeros(off.getGtin())), attribution);
    return new ProductLookupResult(ProductLookupStatus.OFF_CANDIDATE, null, candidate);
  }

  /**
   * Viditelnost pod prahem důvěry (docs/reputace.md) — ACTIVE i DRAFT vidí každý (stejné
   * pravidlo jako {@code productSuggestions} níže), MERGED/REJECTED nikdo. DRAFT musí být
   * dohledatelné i pro jiné přispěvatele, ne jen pro autora — {@code app.catalog.draft-
   * confirmations} vyžaduje potvrzení RŮZNÝMI lidmi (leave-one-out), a k tomu musí ten
   * produkt nejdřív najít/otevřít (skenem kódu, nebo výběrem z {@code productSuggestions} při
   * zakládání podobného zboží) — kdyby `product`/`productByCode` DRAFT cizím lidem schovávaly,
   * potvrzovací mechanismus by nikdy neměl jak nastartovat. Skryté (nahlášené, hidden_at) vidí
   * jen autor, ostatním se tváří jako neexistující — nikdy FORBIDDEN, aby existenci skrytého
   * záznamu nešlo odvodit (CLAUDE.md, pravidlo o neviditelných recenzích platí i tady).
   */
  private boolean isVisible(Product product, ViewerContext viewer) {
    boolean visibleStatus = product.getStatus() == ProductStatus.ACTIVE || product.getStatus() == ProductStatus.DRAFT;
    if (!visibleStatus) return false;
    return product.getHiddenAt() == null || sameUser(product.getCreatedByUserId(), viewer) || viewer.moderator();
  }

  private boolean sameUser(Long createdByUserId, ViewerContext viewer) {
    return createdByUserId != null && createdByUserId.equals(viewer.userId());
  }

  @MutationMapping
  public Product createProduct(@Argument CreateProductInput input, Authentication authentication) {
    return productCatalogService.create(input, viewerContextResolver.resolve(authentication).publicUid());
  }

  @MutationMapping
  public Product createProductFromOff(@Argument CreateProductFromOffInput input, Authentication authentication) {
    return offProductCatalogService.create(input, viewerContextResolver.resolve(authentication).publicUid());
  }

  @SchemaMapping(typeName = "Product", field = "catalogSource")
  public CatalogDataSource catalogSource(Product product) {
    return product.isOffBacked() ? CatalogDataSource.OPEN_FOOD_FACTS : CatalogDataSource.COMMUNITY;
  }

  @SchemaMapping(typeName = "Product", field = "catalogAttribution")
  public String catalogAttribution(Product product) {
    return product.isOffBacked() ? messages.get("attribution.off") : null;
  }

  @SchemaMapping(typeName = "Product", field = "externalImage")
  public ExternalProductImage externalImage(Product product) {
    return externalImage(product.getOffImageFrontUrl(), product.getOffImageFrontSmallUrl(), messages.get("attribution.off"));
  }

  @SchemaMapping(typeName = "Product", field = "brand")
  public ProductBrand brand(Product product) {
    if (product.getExternalBrandName() != null) {
      String name = product.getExternalBrandName();
      return new ProductBrand("off:" + Integer.toUnsignedString(name.toLowerCase().hashCode()), name, slugify(name));
    }
    return product.getBrand() == null ? null : new ProductBrand(
        product.getBrand().getId().toString(), product.getBrand().getName(), product.getBrand().getSlug());
  }

  @MutationMapping
  public Product updateProduct(@Argument Long id, @Argument UpdateProductInput input,
      Authentication authentication) {
    return catalogEditService.updateProduct(id, input, viewerContextResolver.resolve(authentication).publicUid());
  }

  @BatchMapping(typeName = "Product", field = "myPrices")
  public Map<Product, List<MyPrice>> myPrices(List<Product> products, Authentication authentication,
      @ContextValue(name = "displayCurrency", required = false) String displayCurrency) {
    Long viewerId = viewerContextResolver.resolve(authentication).userId();
    Map<Long, List<MyPrice>> byProduct = myPriceService.myPricesByProductId(productIds(products), viewerId, displayCurrency);
    Map<Product, List<MyPrice>> result = new LinkedHashMap<>();
    for (Product p : products) {
      result.put(p, byProduct.getOrDefault(p.getId(), List.of()));
    }
    return result;
  }

  /**
   * Podobné zboží podle názvu (idx_product_name_trgm) — nabídne existující druhové položky
   * dřív, než uživatel založí bezkódový duplikát, a slouží i jako "našli jsme podobné" krok
   * u nového zboží s kódem (docs/reputace.md, "Zboží bez čárového kódu"). Na rozdíl od
   * {@link #product}/{@link #searchProducts} tu DRAFT položky vidí VŠICHNI, ne jen autor —
   * účel je zabránit duplicitám napříč uživateli, ne skrýt nepotvrzené zboží. Skryté
   * (nahlášené) položky se přesto vynechávají.
   */
  @QueryMapping
  public List<Product> productSuggestions(@Argument String name, @Argument Integer first,
      Authentication authentication) {
    if (name == null || name.isBlank()) return List.of();
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    int limit = Math.max(1, Math.min(first == null ? 10 : first, MAX_SUGGESTIONS));
    List<Product> matches = productRepository.findSimilarByName(name.trim(), limit).stream()
        .filter(p -> p.getHiddenAt() == null || sameUser(p.getCreatedByUserId(), viewer) || viewer.moderator())
        .toList();
    return productOverlayService.applyOverlay(matches, viewer.userId());
  }

  @QueryMapping
  public List<Category> categories() {
    return categoryRepository.findAllByOrderByPathAsc();
  }

  /**
   * Lokalizovaný název kategorie (docs/lokalizace.md) — core.category.name je česky (zdroj +
   * fallback), core.category_i18n nese jen jiné jazyky. Přepisuje se tady, na výstupu, NIKDY
   * na spravované entitě uvnitř transakce (stejné pravidlo jako u ostatního "čtení s
   * překryvem" v projektu). Platí pro KAŽDÉ pole typu Category ve schématu — categories()
   * i vnořené Product.category, obojí prochází přes tenhle resolver.
   */
  @BatchMapping(typeName = "Category", field = "name")
  public Map<Category, String> categoryName(List<Category> categories) {
    String locale = LocaleContextHolder.getLocale().getLanguage();
    List<Long> ids = categories.stream().map(Category::getId).toList();
    Map<Long, String> localizedById = categoryI18nRepository.findByCategoryIdInAndLocale(ids, locale).stream()
        .collect(Collectors.toMap(CategoryI18n::getCategoryId, CategoryI18n::getName));
    Map<Category, String> result = new LinkedHashMap<>();
    for (Category category : categories) {
      result.put(category, localizedById.getOrDefault(category.getId(), category.getName()));
    }
    return result;
  }

  /** Opak normalizace — GTIN-14 zpět na EAN bez vedoucích nul, jak ho zná Open Food Facts. */
  private String stripLeadingZeros(String gtin) {
    String stripped = gtin.replaceFirst("^0+(?!$)", "");
    return stripped.isEmpty() ? gtin : stripped;
  }

  /**
   * Vypršelá akční cena (PROMO s promoValidTo v minulosti, docs/datovy-model.md) se tu vyřadí,
   * ale zůstává v agg.price_current i v {@code stats()} — jde jen o to, co appka nabízí jako
   * AKTUÁLNÍ cenu, historie v grafu (agg.price_daily) se nemění a počet přispěvatelů
   * v ProductStats neklesá jen proto, že akce skončila. Filtr je záměrně tady, ne v
   * {@link #pricesByProductId}, kterou sdílí i {@code stats()}.
   */
  @BatchMapping(typeName = "Product", field = "prices")
  public Map<Product, List<PriceCurrent>> prices(List<Product> products) {
    Map<Long, List<PriceCurrent>> byProduct = pricesByProductId(products);
    Map<Product, List<PriceCurrent>> result = new LinkedHashMap<>();
    for (Product p : products) {
      List<PriceCurrent> prices = byProduct.getOrDefault(p.getId(), List.of()).stream()
          .filter(pc -> !isExpiredPromo(pc))
          .toList();
      result.put(p, prices);
    }
    return result;
  }

  private static boolean isExpiredPromo(PriceCurrent priceCurrent) {
    return priceCurrent.getPriceKind() == PriceKind.PROMO
        && priceCurrent.getPromoValidTo() != null
        && priceCurrent.getPromoValidTo().isBefore(LocalDate.now());
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
  public Map<Product, ProductStats> stats(List<Product> products,
      @ContextValue(name = "displayCurrency", required = false) String displayCurrency) {
    Map<Long, List<PriceCurrent>> byProduct = pricesByProductId(products);

    record Raw(int observationCount, int storeCount, OffsetDateTime lastObservedAt,
               BigDecimal bestPrice, BigDecimal bestUnitPrice, Long cheapestStoreId, String bestPriceCurrency) {
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
      // Uvnitř REGULAR ještě dominantní měna (nejvíc n_obs) — bez tohohle by .min() přes
      // PriceCurrent.unitPrice srovnávalo číslo v CZK s číslem v EUR, jako by to byla stejná
      // měna, a "nejlevnější" by bylo jen náhodou nejmenší číslo (docs/lokalizace.md).
      Map<String, List<PriceCurrent>> regularByCurrency = prices.stream()
          .filter(pc -> pc.getPriceKind() == PriceKind.REGULAR && pc.getUnitPrice() != null)
          .collect(Collectors.groupingBy(PriceCurrent::getCurrency));
      PriceCurrent cheapest = regularByCurrency.values().stream()
          .max(Comparator.comparingInt(group -> group.stream().mapToInt(PriceCurrent::getNObs).sum()))
          .orElse(List.<PriceCurrent>of()).stream()
          .min(Comparator.comparing(PriceCurrent::getUnitPrice))
          .orElse(null);
      rawByProduct.put(p.getId(), new Raw(observationCount, storeCount, lastObservedAt,
          cheapest == null ? null : cheapest.getPriceAmount(),
          cheapest == null ? null : cheapest.getUnitPrice(),
          cheapest == null ? null : cheapest.getStoreId(),
          cheapest == null ? null : cheapest.getCurrency()));
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
      // Kurz k lastObservedAt, ne dnešní (docs/lokalizace.md) — "nejlevnější" je pořád ta samá
      // konkrétní observace, jen zobrazená v jiné měně.
      ConvertedPrice converted = displayCurrency == null || raw.lastObservedAt() == null
          ? null
          : ConvertedPrice.from(fxRateService.convert(raw.bestPrice(), raw.bestPriceCurrency(), displayCurrency,
              raw.lastObservedAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate()));
      result.put(p, new ProductStats(raw.observationCount(), raw.storeCount(), raw.lastObservedAt(),
          raw.bestPrice(), raw.bestUnitPrice(), cheapestStore, raw.bestPriceCurrency(), converted));
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

  @BatchMapping(typeName = "Product", field = "photos")
  public Map<Product, List<Photo>> photos(List<Product> products, Authentication authentication) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    Map<Long, List<Photo>> byProduct = mediaService.photosForBatch(RecordType.PRODUCT, productIds(products), viewer);
    Map<Product, List<Photo>> result = new LinkedHashMap<>();
    for (Product p : products) {
      result.put(p, byProduct.getOrDefault(p.getId(), List.of()));
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
        messages.get("attribution.off")));
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

  private ExternalProductImage externalImage(String url, String thumbnailUrl, String attribution) {
    String effectiveUrl = url != null ? url : thumbnailUrl;
    if (effectiveUrl == null) return null;
    return new ExternalProductImage(effectiveUrl, thumbnailUrl != null ? thumbnailUrl : effectiveUrl, attribution);
  }

  private String slugify(String value) {
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "").toLowerCase();
    return normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
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
