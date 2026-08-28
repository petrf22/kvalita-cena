package cz.kvalitacena.service;

import cz.kvalitacena.controller.CreateProductFromOffInput;
import cz.kvalitacena.controller.UpdateProductInput;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.CodeType;
import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductCode;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.ProductCodeRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.exception.DuplicateException;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.TooManyRequestsException;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.CatalogRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Založení zboží nad potvrzeným OFF náhledem (CreateProductFromOffInput, ProductGraphQlController
 * .createProductFromOff) — na rozdíl od ProductCatalogServiceTest (ruční zadání) tu jde hlavně
 * o to, že komunitní sloupce core.product smí zůstat NULL, když hodnotu dodá OFF snapshot
 * (docs/datovy-model.md, "Open Food Facts"), a že se nikdy nekopíruje do core.* — jen odchylka
 * od OFF/komunitního základu skončí jako core.product_user_edit přes CatalogEditService.
 */
@ExtendWith(MockitoExtension.class)
class OffProductCatalogServiceTest {

  private static final Long USER_ID = 42L;
  private static final Long CATEGORY_ID = 5L;
  private static final Long PRODUCT_ID = 7L;
  private static final UUID PUBLIC_UID = UUID.randomUUID();
  private static final String RAW_CODE = "8594001234578";
  private static final String GTIN = "08594001234578";

  @Mock private ProductRepository productRepository;
  @Mock private ProductCodeRepository productCodeRepository;
  @Mock private AppUserRepository appUserRepository;
  @Mock private CategoryRepository categoryRepository;
  @Mock private BrandResolutionService brandResolutionService;
  @Mock private CatalogRateLimiter catalogRateLimiter;
  @Mock private OpenFoodFactsService offService;
  @Mock private CatalogEditService catalogEditService;
  @Mock private TrustLevelService trustLevelService;

  private final OffNetContentConverter netContentConverter = new OffNetContentConverter();

  private OffProductCatalogService service() {
    return new OffProductCatalogService(productRepository, productCodeRepository, appUserRepository,
        categoryRepository, brandResolutionService, catalogRateLimiter, offService, netContentConverter,
        catalogEditService, trustLevelService);
  }

  private CreateProductFromOffInput input(String name, Long categoryId, UnitBase unitBase) {
    return new CreateProductFromOffInput(RAW_CODE, name, null, categoryId, unitBase, null, null, null, false);
  }

  private void givenLoggedInUser() {
    when(appUserRepository.findByPublicUid(PUBLIC_UID)).thenReturn(Optional.of(AppUser.builder().id(USER_ID).build()));
  }

  private void givenNoExistingProductCode() {
    when(productCodeRepository.findFirstByCodeAndCodeType(any(), any())).thenReturn(Optional.empty());
  }

  private void givenCategoryExists() {
    when(categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.of(Category.builder().id(CATEGORY_ID).build()));
  }

  private void givenRateLimitOk() {
    when(catalogRateLimiter.tryAcquireProductCreation(PUBLIC_UID)).thenReturn(true);
  }

  private void givenSaveEchoesInputWithId() {
    when(productRepository.saveAndFlush(any())).thenAnswer(inv -> {
      Product p = inv.getArgument(0);
      p.setId(PRODUCT_ID);
      return p;
    });
  }

  private static final String OFF_CATEGORY_SLUG = "sry";

  private OffProduct offWithFullData() {
    return OffProduct.builder().gtin(GTIN).productName("Šumavský eidam")
        .productQuantity(new BigDecimal("300")).productQuantityUnit("G")
        .mappedCategorySlug(OFF_CATEGORY_SLUG).build();
  }

  /** Mapovaná OFF kategorie existuje i v core.category — jinak by fallback čekal input.categoryId. */
  private void givenOffCategoryMaps() {
    when(categoryRepository.findBySlug(OFF_CATEGORY_SLUG))
        .thenReturn(Optional.of(Category.builder().id(CATEGORY_ID).build()));
  }

  @Test
  void anonymousCannotCreate() {
    assertThatThrownBy(() -> service().create(input("X", CATEGORY_ID, UnitBase.MASS), null))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void offUnavailableIsReportedAsValidationError() {
    givenLoggedInUser();
    when(offService.lookup(RAW_CODE)).thenReturn(OffLookupResult.unavailable());

    assertThatThrownBy(() -> service().create(input("X", CATEGORY_ID, UnitBase.MASS), PUBLIC_UID))
        .isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(((ValidationException) e).getCode()).isEqualTo(ErrorCode.OFF_UNAVAILABLE));
  }

  @Test
  void offNotFoundIsReportedAsValidationError() {
    givenLoggedInUser();
    when(offService.lookup(RAW_CODE)).thenReturn(OffLookupResult.notFound(
        OffProduct.builder().gtin(GTIN).build()));

    assertThatThrownBy(() -> service().create(input("X", CATEGORY_ID, UnitBase.MASS), PUBLIC_UID))
        .isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(((ValidationException) e).getCode()).isEqualTo(ErrorCode.OFF_PRODUCT_NOT_FOUND));
  }

  @Test
  void existingCodeIsReportedAsDuplicate() {
    givenLoggedInUser();
    when(offService.lookup(RAW_CODE)).thenReturn(OffLookupResult.found(offWithFullData()));
    Product existingProduct = Product.builder().id(123L).build();
    when(productCodeRepository.findFirstByCodeAndCodeType(GTIN, CodeType.GTIN))
        .thenReturn(Optional.of(ProductCode.builder().product(existingProduct).build()));

    assertThatThrownBy(() -> service().create(input("X", CATEGORY_ID, UnitBase.MASS), PUBLIC_UID))
        .isInstanceOf(DuplicateException.class)
        .satisfies(e -> assertThat(((DuplicateException) e).getExistingId()).isEqualTo(123L));
  }

  @Test
  void rateLimitExceededThrows() {
    givenLoggedInUser();
    when(offService.lookup(RAW_CODE)).thenReturn(OffLookupResult.found(offWithFullData()));
    givenNoExistingProductCode();
    when(catalogRateLimiter.tryAcquireProductCreation(PUBLIC_UID)).thenReturn(false);

    assertThatThrownBy(() -> service().create(input("X", CATEGORY_ID, UnitBase.MASS), PUBLIC_UID))
        .isInstanceOf(TooManyRequestsException.class);
  }

  /**
   * Klíčová vlastnost ODbL oddělení (CLAUDE.md, "core.product je jádro aplikace" +
   * docs/datovy-model.md) — když OFF dodá název/gramáž, core.product ty sloupce NIKDY nedostane
   * vyplněné, i když je uživatel v potvrzovacím náhledu viděl. Efektivní hodnotu skládá až
   * ProductOverlayService při čtení.
   */
  @Test
  void offSuppliedFieldsStayNullOnTheSavedRow() {
    givenLoggedInUser();
    when(offService.lookup(RAW_CODE)).thenReturn(OffLookupResult.found(offWithFullData()));
    givenNoExistingProductCode();
    givenRateLimitOk();
    givenOffCategoryMaps();
    when(trustLevelService.isTrusted(any())).thenReturn(true);
    givenSaveEchoesInputWithId();

    ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
    service().create(input(null, null, null), PUBLIC_UID);

    verify(productRepository).saveAndFlush(captor.capture());
    Product saved = captor.getValue();
    assertThat(saved.getName()).isNull();
    assertThat(saved.getCategory()).isNull();
    assertThat(saved.getUnitBase()).isNull();
    assertThat(saved.getNetContentBase()).isNull();
    assertThat(saved.isGeneric()).isFalse();
  }

  @Test
  void trustedAuthorProductIsActive() {
    givenLoggedInUser();
    when(offService.lookup(RAW_CODE)).thenReturn(OffLookupResult.found(offWithFullData()));
    givenNoExistingProductCode();
    givenRateLimitOk();
    givenOffCategoryMaps();
    when(trustLevelService.isTrusted(any())).thenReturn(true);
    givenSaveEchoesInputWithId();

    ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
    service().create(input(null, null, null), PUBLIC_UID);

    verify(productRepository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(ProductStatus.ACTIVE);
  }

  /**
   * Kód sám je dost silná identifikace zboží (stejný důvod jako ProductCatalogService.create),
   * ale autor OFF ověřený není — nedůvěryhodný účet nesmí OFF cestou obejít práh T2
   * (docs/reputace.md), stejně jako u ručně zadaného zboží s kódem.
   */
  @Test
  void untrustedAuthorProductIsDraft() {
    givenLoggedInUser();
    when(offService.lookup(RAW_CODE)).thenReturn(OffLookupResult.found(offWithFullData()));
    givenNoExistingProductCode();
    givenRateLimitOk();
    givenOffCategoryMaps();
    when(trustLevelService.isTrusted(any())).thenReturn(false);
    givenSaveEchoesInputWithId();

    ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
    service().create(input(null, null, null), PUBLIC_UID);

    verify(productRepository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(ProductStatus.DRAFT);
  }

  @Test
  void missingOffNameRequiresInputName() {
    givenLoggedInUser();
    when(offService.lookup(RAW_CODE)).thenReturn(OffLookupResult.found(
        OffProduct.builder().gtin(GTIN).build()));
    givenNoExistingProductCode();
    givenRateLimitOk();

    assertThatThrownBy(() -> service().create(input(null, CATEGORY_ID, UnitBase.MASS), PUBLIC_UID))
        .isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(((ValidationException) e).getCode()).isEqualTo(ErrorCode.PRODUCT_NAME_REQUIRED));
  }

  @Test
  void missingOffNameFallsBackToInputName() {
    givenLoggedInUser();
    when(offService.lookup(RAW_CODE)).thenReturn(OffLookupResult.found(
        OffProduct.builder().gtin(GTIN).build()));
    givenNoExistingProductCode();
    givenRateLimitOk();
    givenCategoryExists();
    when(trustLevelService.isTrusted(any())).thenReturn(true);
    givenSaveEchoesInputWithId();

    ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
    service().create(input(" Domácí chleba ", CATEGORY_ID, UnitBase.MASS), PUBLIC_UID);

    verify(productRepository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo("Domácí chleba");
  }

  @Test
  void missingOffCategoryRequiresInputCategory() {
    givenLoggedInUser();
    when(offService.lookup(RAW_CODE)).thenReturn(OffLookupResult.found(
        OffProduct.builder().gtin(GTIN).productName("Chleba").build()));
    givenNoExistingProductCode();
    givenRateLimitOk();

    assertThatThrownBy(() -> service().create(input(null, null, null), PUBLIC_UID))
        .isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(((ValidationException) e).getCode())
            .isEqualTo(ErrorCode.PRODUCT_CATEGORY_REQUIRED));
  }

  @Test
  void unknownInputCategoryIsRejected() {
    givenLoggedInUser();
    when(offService.lookup(RAW_CODE)).thenReturn(OffLookupResult.found(
        OffProduct.builder().gtin(GTIN).productName("Chleba").build()));
    givenNoExistingProductCode();
    givenRateLimitOk();
    when(categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().create(input(null, CATEGORY_ID, null), PUBLIC_UID))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void missingOffQuantityRequiresInputUnitBase() {
    givenLoggedInUser();
    when(offService.lookup(RAW_CODE)).thenReturn(OffLookupResult.found(
        OffProduct.builder().gtin(GTIN).productName("Chleba").build()));
    givenNoExistingProductCode();
    givenRateLimitOk();
    givenCategoryExists();

    assertThatThrownBy(() -> service().create(input(null, CATEGORY_ID, null), PUBLIC_UID))
        .isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(((ValidationException) e).getCode())
            .isEqualTo(ErrorCode.PRODUCT_UNIT_BASE_REQUIRED));
  }

  /**
   * Odchylka mezi potvrzeným náhledem a OFF/komunitním základem musí skončit jako
   * core.product_user_edit — OffProductCatalogService proto vždy zavolá stejný patch mechanismus
   * jako ruční editace (CatalogEditService.updateProduct), ne přímý zápis do core.product.
   */
  @Test
  void confirmedValuesGoThroughUpdateProductPatch() {
    givenLoggedInUser();
    when(offService.lookup(RAW_CODE)).thenReturn(OffLookupResult.found(offWithFullData()));
    givenNoExistingProductCode();
    givenRateLimitOk();
    givenOffCategoryMaps();
    when(trustLevelService.isTrusted(any())).thenReturn(true);
    givenSaveEchoesInputWithId();
    Product patched = Product.builder().id(PRODUCT_ID).build();
    when(catalogEditService.updateProduct(eq(PRODUCT_ID), any(), eq(PUBLIC_UID))).thenReturn(patched);

    CreateProductFromOffInput confirmed = new CreateProductFromOffInput(
        RAW_CODE, "Šumavský eidam plátky", null, null, null, null, null, 2, false);
    Product result = service().create(confirmed, PUBLIC_UID);

    assertThat(result).isSameAs(patched);
    ArgumentCaptor<UpdateProductInput> captor = ArgumentCaptor.forClass(UpdateProductInput.class);
    verify(catalogEditService).updateProduct(eq(PRODUCT_ID), captor.capture(), eq(PUBLIC_UID));
    assertThat(captor.getValue().name()).isEqualTo("Šumavský eidam plátky");
    assertThat(captor.getValue().piecesInPack()).isEqualTo(2);
  }

  @Test
  void duplicateInsertRaceIsReportedAsDuplicate() {
    givenLoggedInUser();
    when(offService.lookup(RAW_CODE)).thenReturn(OffLookupResult.found(offWithFullData()));
    givenNoExistingProductCode();
    givenRateLimitOk();
    givenOffCategoryMaps();
    when(trustLevelService.isTrusted(any())).thenReturn(true);
    when(productRepository.saveAndFlush(any()))
        .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_product_code"));

    assertThatThrownBy(() -> service().create(input(null, null, null), PUBLIC_UID))
        .isInstanceOf(DuplicateException.class);
    verify(catalogEditService, never()).updateProduct(any(), any(), any());
  }
}
