package cz.kvalitacena.controller;

import cz.kvalitacena.config.ExternalLinkProperties;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.CodeType;
import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductCode;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.db.repo.CategoryI18nRepository;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.PriceCurrentRepository;
import cz.kvalitacena.db.repo.ProductCodeRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.security.ViewerContext;
import cz.kvalitacena.security.ViewerContextResolver;
import cz.kvalitacena.service.CatalogEditService;
import cz.kvalitacena.service.CountryResolver;
import cz.kvalitacena.service.fx.FxRateService;
import cz.kvalitacena.service.MediaService;
import cz.kvalitacena.service.MyPriceService;
import cz.kvalitacena.service.OffLookupResult;
import cz.kvalitacena.service.OffNetContentConverter;
import cz.kvalitacena.service.OffProductCatalogService;
import cz.kvalitacena.service.OpenFoodFactsService;
import cz.kvalitacena.service.ProductCatalogService;
import cz.kvalitacena.service.ProductOverlayService;
import cz.kvalitacena.service.ProductSearchService;
import cz.kvalitacena.service.QualityRatingService;
import cz.kvalitacena.service.TestMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code productLookupByCode} je jediný vstupní bod pro sken (schema.graphqls) — nejdřív vlastní
 * katalog (přes stejné {@code isVisible} pravidlo jako {@code product}/{@code productByCode}),
 * pak lokální OFF snapshot / API. Logika žije přímo v {@link ProductGraphQlController} (žádná
 * samostatná service), a projekt záměrně nezvedá Spring kontext v testech (viz komentář
 * v {@code GraphQlErrorLocalizationTest}) — proto testujeme kontroler jako obyčejný POJO.
 */
@ExtendWith(MockitoExtension.class)
class ProductGraphQlControllerLookupByCodeTest {

  private static final String RAW_CODE = "8594001234578";
  private static final String GTIN = "08594001234578";
  private static final Long VIEWER_ID = 42L;
  private static final Long CATEGORY_ID = 5L;

  @Mock private ProductRepository productRepository;
  @Mock private PriceCurrentRepository priceCurrentRepository;
  @Mock private ProductCodeRepository productCodeRepository;
  @Mock private StoreRepository storeRepository;
  @Mock private CategoryRepository categoryRepository;
  @Mock private CategoryI18nRepository categoryI18nRepository;
  @Mock private ProductSearchService productSearchService;
  @Mock private QualityRatingService qualityRatingService;
  @Mock private ProductCatalogService productCatalogService;
  @Mock private OffProductCatalogService offProductCatalogService;
  @Mock private OpenFoodFactsService openFoodFactsService;
  @Mock private ProductOverlayService productOverlayService;
  @Mock private CatalogEditService catalogEditService;
  @Mock private MyPriceService myPriceService;
  @Mock private MediaService mediaService;
  @Mock private ViewerContextResolver viewerContextResolver;
  @Mock private CountryResolver countryResolver;
  @Mock private FxRateService fxRateService;

  private final OffNetContentConverter offNetContentConverter = new OffNetContentConverter();
  private final ExternalLinkProperties externalLinkProperties = new ExternalLinkProperties();

  {
    externalLinkProperties.getOpenFoodFacts().setProductUrlTemplate("https://world.openfoodfacts.org/product/{barcode}");
  }

  private ProductGraphQlController controller() {
    return new ProductGraphQlController(productRepository, priceCurrentRepository, productCodeRepository,
        storeRepository, categoryRepository, categoryI18nRepository, productSearchService, qualityRatingService,
        productCatalogService, offProductCatalogService, openFoodFactsService, offNetContentConverter,
        productOverlayService, catalogEditService, myPriceService, mediaService, viewerContextResolver,
        externalLinkProperties, TestMessages.instance(), countryResolver, fxRateService);
  }

  private void givenAnonymousViewer() {
    when(viewerContextResolver.resolve(any())).thenReturn(ViewerContext.ANONYMOUS);
  }

  @Test
  void invalidCodeIsNotFoundWithoutTouchingCatalogOrOff() {
    givenAnonymousViewer();

    ProductLookupResult result = controller().productLookupByCode("abc", null);

    assertThat(result.status()).isEqualTo(ProductLookupStatus.NOT_FOUND);
    verify(productCodeRepository, never()).findFirstByCodeAndCodeType(any(), any());
    verify(openFoodFactsService, never()).lookup(any());
  }

  @Test
  void visibleExistingProductIsReturnedWithoutCallingOff() {
    givenAnonymousViewer();
    Product stored = Product.builder().id(1L).status(ProductStatus.ACTIVE).build();
    Product overlaid = stored.toBuilder().name("Existující zboží").build();
    when(productCodeRepository.findFirstByCodeAndCodeType(GTIN, CodeType.GTIN))
        .thenReturn(Optional.of(ProductCode.builder().product(stored).build()));
    when(productOverlayService.applyOverlay(stored, null)).thenReturn(overlaid);

    ProductLookupResult result = controller().productLookupByCode(RAW_CODE, null);

    assertThat(result.status()).isEqualTo(ProductLookupStatus.EXISTING);
    assertThat(result.product()).isSameAs(overlaid);
    assertThat(result.candidate()).isNull();
    verify(openFoodFactsService, never()).lookup(any());
  }

  /**
   * Skryté (nahlášené a potvrzené moderátorem) zboží se anonymnímu/cizímu čtenáři musí tvářit
   * jako neexistující (CLAUDE.md — stejné pravidlo jako u neviditelných recenzí), ne jako
   * EXISTING. Lookup proto musí propadnout do OFF větve stejně, jako by kód v katalogu nebyl.
   */
  @Test
  void hiddenProductIsInvisibleAndFallsThroughToOff() {
    givenAnonymousViewer();
    Product hidden = Product.builder().id(1L).status(ProductStatus.ACTIVE)
        .createdByUserId(99L).hiddenAt(java.time.OffsetDateTime.now()).build();
    when(productCodeRepository.findFirstByCodeAndCodeType(GTIN, CodeType.GTIN))
        .thenReturn(Optional.of(ProductCode.builder().product(hidden).build()));
    when(openFoodFactsService.lookup(RAW_CODE)).thenReturn(OffLookupResult.notFound(
        OffProduct.builder().gtin(GTIN).build()));

    ProductLookupResult result = controller().productLookupByCode(RAW_CODE, null);

    assertThat(result.status()).isEqualTo(ProductLookupStatus.NOT_FOUND);
    verify(productOverlayService, never()).applyOverlay(any(Product.class), any());
  }

  @Test
  void offUnavailableIsReported() {
    givenAnonymousViewer();
    when(productCodeRepository.findFirstByCodeAndCodeType(GTIN, CodeType.GTIN)).thenReturn(Optional.empty());
    when(openFoodFactsService.lookup(RAW_CODE)).thenReturn(OffLookupResult.unavailable());

    ProductLookupResult result = controller().productLookupByCode(RAW_CODE, null);

    assertThat(result.status()).isEqualTo(ProductLookupStatus.OFF_UNAVAILABLE);
  }

  @Test
  void offCandidateMapsFieldsAndNeverTouchesCoreProduct() {
    givenAnonymousViewer();
    when(productCodeRepository.findFirstByCodeAndCodeType(GTIN, CodeType.GTIN)).thenReturn(Optional.empty());
    OffProduct off = OffProduct.builder().gtin(GTIN).productName("Šumavský eidam")
        .brandName("Mlékárna Klatovy").mappedCategorySlug("sry")
        .productQuantity(new BigDecimal("300")).productQuantityUnit("G")
        .imageFrontUrl("https://images.openfoodfacts.org/front.jpg")
        .imageFrontSmallUrl("https://images.openfoodfacts.org/front.small.jpg").build();
    when(openFoodFactsService.lookup(RAW_CODE)).thenReturn(OffLookupResult.found(off));
    Category category = Category.builder().id(CATEGORY_ID).slug("sry").name("Sýry").build();
    when(categoryRepository.findBySlug("sry")).thenReturn(Optional.of(category));

    ProductLookupResult result = controller().productLookupByCode(RAW_CODE, null);

    assertThat(result.status()).isEqualTo(ProductLookupStatus.OFF_CANDIDATE);
    assertThat(result.product()).isNull();
    ExternalProductCandidate candidate = result.candidate();
    assertThat(candidate.code()).isEqualTo(RAW_CODE);
    assertThat(candidate.name()).isEqualTo("Šumavský eidam");
    assertThat(candidate.brandName()).isEqualTo("Mlékárna Klatovy");
    assertThat(candidate.category()).isSameAs(category);
    assertThat(candidate.unitBase()).isEqualTo(UnitBase.MASS);
    assertThat(candidate.netContentValue()).isEqualByComparingTo("300");
    assertThat(candidate.netContentUom()).isEqualTo(NetContentUom.G);
    assertThat(candidate.image().url()).isEqualTo("https://images.openfoodfacts.org/front.jpg");
    assertThat(candidate.sourceUrl()).isEqualTo("https://world.openfoodfacts.org/product/" + RAW_CODE);
    assertThat(candidate.attribution()).contains("Open Food Facts");
    verify(productRepository, never()).saveAndFlush(any());
  }
}
