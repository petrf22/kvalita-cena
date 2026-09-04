package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.controller.CreateProductInput;
import cz.kvalitacena.controller.PublicationState;
import cz.kvalitacena.controller.PublicationStatus;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.CodeType;
import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductCode;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.entity.ProductScope;
import cz.kvalitacena.db.entity.RetailChain;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.ProductCodeRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.DuplicateException;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Založení zboží — s naskenovaným EANem i bez něj (bezkódová druhová položka, docs/reputace.md,
 * "Zboží bez čárového kódu"). Vyžaduje přihlášení stejně jako založení obchodu (StoreServiceTest).
 */
@ExtendWith(MockitoExtension.class)
class ProductCatalogServiceTest {

  private static final Long USER_ID = 42L;
  private static final Long CATEGORY_ID = 5L;
  private static final Long PRODUCT_ID = 7L;
  private static final Long STORE_ID = 9L;
  private static final UUID PUBLIC_UID = UUID.randomUUID();

  @Mock
  private ProductRepository productRepository;
  @Mock
  private StoreRepository storeRepository;
  @Mock
  private BrandResolutionService brandResolutionService;
  @Mock
  private CategoryRepository categoryRepository;
  @Mock
  private ProductCodeRepository productCodeRepository;
  @Mock
  private PriceObservationRepository priceObservationRepository;
  @Mock
  private AppUserRepository appUserRepository;
  @Mock
  private CatalogRateLimiter catalogRateLimiter;
  @Mock
  private DuplicateLookupService duplicateLookupService;
  @Mock
  private TrustLevelService trustLevelService;

  private final CatalogProperties catalogProperties = new CatalogProperties();

  private ProductCatalogService service() {
    catalogProperties.setDraftConfirmations(3);
    catalogProperties.setMaxUnconfirmedDrafts(15);
    return new ProductCatalogService(productRepository, storeRepository, brandResolutionService, categoryRepository,
        productCodeRepository, priceObservationRepository, appUserRepository, catalogProperties,
        catalogRateLimiter, duplicateLookupService, trustLevelService, new ProductScopeService());
  }

  private CreateProductInput input(String code) {
    return new CreateProductInput("Chléb konzumní", null, CATEGORY_ID, UnitBase.MASS,
        new BigDecimal("1200"), NetContentUom.G, null, false, STORE_ID, code);
  }

  private void givenLoggedInUser() {
    when(appUserRepository.findByPublicUid(PUBLIC_UID)).thenReturn(Optional.of(AppUser.builder().id(USER_ID).build()));
  }

  private void givenCategoryExists() {
    when(categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.of(Category.builder().id(CATEGORY_ID).build()));
  }

  private void givenStoreExists(Store store) {
    when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store));
    when(catalogRateLimiter.tryAcquireProductCreationInStore(PUBLIC_UID, store.getId())).thenReturn(true);
  }

  @Test
  void anonymousCannotCreateProduct() {
    assertThatThrownBy(() -> service().create(input(null), null)).isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void blankNameIsRejected() {
    givenLoggedInUser();
    CreateProductInput blank = new CreateProductInput(" ", null, CATEGORY_ID, UnitBase.MASS,
        null, null, null, false, STORE_ID, null);
    assertThatThrownBy(() -> service().create(blank, PUBLIC_UID)).isInstanceOf(ValidationException.class);
  }

  @Test
  void missingCategoryIsRejected() {
    givenLoggedInUser();
    CreateProductInput noCategory = new CreateProductInput("Chléb", null, null, UnitBase.MASS,
        null, null, null, false, STORE_ID, null);
    assertThatThrownBy(() -> service().create(noCategory, PUBLIC_UID)).isInstanceOf(ValidationException.class);
  }

  @Test
  void unknownCategoryIsRejected() {
    givenLoggedInUser();
    when(categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service().create(input(null), PUBLIC_UID)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void rateLimitExceededThrows() {
    givenLoggedInUser();
    givenCategoryExists();
    when(catalogRateLimiter.tryAcquireProductCreation(PUBLIC_UID)).thenReturn(false);
    assertThatThrownBy(() -> service().create(input(null), PUBLIC_UID)).isInstanceOf(TooManyRequestsException.class);
  }

  @Test
  void existingCodeIsReportedAsDuplicate() {
    givenLoggedInUser();
    givenCategoryExists();
    Product existingProduct = Product.builder().id(123L).build();
    when(productCodeRepository.findFirstByCodeAndCodeType(any(), any()))
        .thenReturn(Optional.of(ProductCode.builder().product(existingProduct).build()));

    assertThatThrownBy(() -> service().create(input("8594001234578"), PUBLIC_UID))
        .isInstanceOf(DuplicateException.class)
        .satisfies(e -> assertThat(((DuplicateException) e).getExistingId()).isEqualTo(123L));
  }

  @Test
  void productWithCodeIsActiveAndNotGeneric() {
    givenLoggedInUser();
    givenCategoryExists();
    when(catalogRateLimiter.tryAcquireProductCreation(PUBLIC_UID)).thenReturn(true);
    when(trustLevelService.isTrusted(any())).thenReturn(true);
    when(productRepository.saveAndFlush(any())).thenAnswer(inv -> {
      Product p = inv.getArgument(0);
      p.setId(PRODUCT_ID);
      return p;
    });

    Product product = service().create(input("8594001234578"), PUBLIC_UID);

    assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    assertThat(product.isGeneric()).isFalse();
    // Gramáž 1200 G → 1.2 KG (základní jednotka).
    assertThat(product.getNetContentBase()).isEqualByComparingTo("1.2");
    verify(productCodeRepository).save(any(ProductCode.class));
  }

  @Test
  void productWithCodeFromUntrustedAuthorIsDraft() {
    givenLoggedInUser();
    givenCategoryExists();
    when(catalogRateLimiter.tryAcquireProductCreation(PUBLIC_UID)).thenReturn(true);
    when(trustLevelService.isTrusted(any())).thenReturn(false);
    when(productRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    Product product = service().create(input("8594001234578"), PUBLIC_UID);

    assertThat(product.getStatus()).isEqualTo(ProductStatus.DRAFT);
    assertThat(product.isGeneric()).isFalse();
  }

  @Test
  void codelessProductIsDraftAndGeneric() {
    givenLoggedInUser();
    givenCategoryExists();
    when(catalogRateLimiter.tryAcquireProductCreation(PUBLIC_UID)).thenReturn(true);
    givenStoreExists(Store.builder().id(STORE_ID).build());
    when(productRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    Product product = service().create(input(null), PUBLIC_UID);

    assertThat(product.getStatus()).isEqualTo(ProductStatus.DRAFT);
    assertThat(product.isGeneric()).isTrue();
    assertThat(product.getCatalogScope()).isEqualTo(ProductScope.STORE);
    assertThat(product.getScopeStore().getId()).isEqualTo(STORE_ID);
    verify(productCodeRepository, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void codelessProductRequiresStore() {
    givenLoggedInUser();
    givenCategoryExists();
    when(catalogRateLimiter.tryAcquireProductCreation(PUBLIC_UID)).thenReturn(true);
    CreateProductInput withoutStore = new CreateProductInput("Chléb", null, CATEGORY_ID,
        UnitBase.MASS, null, null, null, true, null, null);

    assertThatThrownBy(() -> service().create(withoutStore, PUBLIC_UID))
        .isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(((ValidationException) e).getCode())
            .isEqualTo(cz.kvalitacena.exception.ErrorCode.PRODUCT_STORE_REQUIRED));
  }

  @Test
  void tooManyUnconfirmedDraftsBlocksAnotherCodelessProduct() {
    givenLoggedInUser();
    givenCategoryExists();
    when(catalogRateLimiter.tryAcquireProductCreation(PUBLIC_UID)).thenReturn(true);
    when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(Store.builder().id(STORE_ID).build()));
    when(productRepository.countByCreatedByUserIdAndGenericAndStatus(USER_ID, true, ProductStatus.DRAFT))
        .thenReturn(15L);

    assertThatThrownBy(() -> service().create(input(null), PUBLIC_UID))
        .isInstanceOf(TooManyRequestsException.class)
        .satisfies(e -> assertThat(((TooManyRequestsException) e).getCode())
            .isEqualTo(cz.kvalitacena.exception.ErrorCode.PRODUCT_TOO_MANY_UNCONFIRMED));
  }

  @Test
  void perStoreDailyLimitBlocksAnotherCodelessProduct() {
    givenLoggedInUser();
    givenCategoryExists();
    when(catalogRateLimiter.tryAcquireProductCreation(PUBLIC_UID)).thenReturn(true);
    when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(Store.builder().id(STORE_ID).build()));
    when(catalogRateLimiter.tryAcquireProductCreationInStore(PUBLIC_UID, STORE_ID)).thenReturn(false);

    assertThatThrownBy(() -> service().create(input(null), PUBLIC_UID))
        .isInstanceOf(TooManyRequestsException.class);
  }

  /** Zboží s kódem oba bezkódové stropy míjí — kód sám je dost silná identifikace. */
  @Test
  void codedProductIgnoresCodelessLimits() {
    givenLoggedInUser();
    givenCategoryExists();
    when(catalogRateLimiter.tryAcquireProductCreation(PUBLIC_UID)).thenReturn(true);
    when(trustLevelService.isTrusted(any())).thenReturn(true);
    when(productRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    Product product = service().create(input("8594001234578"), PUBLIC_UID);

    assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    verify(catalogRateLimiter, org.mockito.Mockito.never()).tryAcquireProductCreationInStore(any(), any());
  }

  @Test
  void variableWeightProductAlwaysHasUnitNetContentBase() {
    givenLoggedInUser();
    givenCategoryExists();
    when(catalogRateLimiter.tryAcquireProductCreation(PUBLIC_UID)).thenReturn(true);
    when(productRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    CreateProductInput variableWeight = new CreateProductInput("Sýr eidam", null, CATEGORY_ID,
        UnitBase.MASS, new BigDecimal("300"), NetContentUom.G, null, true, STORE_ID, null);
    givenStoreExists(Store.builder().id(STORE_ID).chain(RetailChain.builder().id(11L).build()).build());

    Product product = service().create(variableWeight, PUBLIC_UID);

    assertThat(product.getNetContentBase()).isEqualByComparingTo("1");
    assertThat(product.getCatalogScope()).isEqualTo(ProductScope.CHAIN);
    assertThat(product.getScopeChain().getId()).isEqualTo(11L);
  }

  @Test
  void mismatchedUnitAndUomIsRejected() {
    givenLoggedInUser();
    givenCategoryExists();
    when(catalogRateLimiter.tryAcquireProductCreation(PUBLIC_UID)).thenReturn(true);

    CreateProductInput mismatched = new CreateProductInput("Mléko", null, CATEGORY_ID,
        UnitBase.VOLUME, new BigDecimal("1"), NetContentUom.G, null, false, STORE_ID, null);

    assertThatThrownBy(() -> service().create(mismatched, PUBLIC_UID))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void draftIsPromotedToActiveOnceConfirmationThresholdReached() {
    when(productRepository.findById(PRODUCT_ID))
        .thenReturn(Optional.of(
            Product.builder().id(PRODUCT_ID).status(ProductStatus.DRAFT).createdByUserId(USER_ID).build()));
    // Leave-one-out (docs/reputace.md) — jen jiní přispěvatelé než autor se počítají.
    when(priceObservationRepository.countDistinctProductContributorsExcluding(PRODUCT_ID, USER_ID))
        .thenReturn(3L);

    service().promoteIfConfirmed(PRODUCT_ID);

    ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
    verify(productRepository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(ProductStatus.ACTIVE);
  }

  @Test
  void draftStaysDraftBelowConfirmationThreshold() {
    when(productRepository.findById(PRODUCT_ID))
        .thenReturn(Optional.of(
            Product.builder().id(PRODUCT_ID).status(ProductStatus.DRAFT).createdByUserId(USER_ID).build()));
    when(priceObservationRepository.countDistinctProductContributorsExcluding(PRODUCT_ID, USER_ID))
        .thenReturn(2L);

    service().promoteIfConfirmed(PRODUCT_ID);

    verify(productRepository, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void activeProductIsNeverTouchedByPromotion() {
    when(productRepository.findById(PRODUCT_ID))
        .thenReturn(Optional.of(Product.builder().id(PRODUCT_ID).status(ProductStatus.ACTIVE).build()));

    service().promoteIfConfirmed(PRODUCT_ID);

    verify(productRepository, org.mockito.Mockito.never()).save(any());
    verify(priceObservationRepository, org.mockito.Mockito.never())
        .countDistinctProductContributorsExcluding(any(), any());
  }

  // --- productStatus / confirmationsForProducts (sdíleno s MyContributionsService a ModerationService) ---

  @Test
  void draftProductStatusReportsExactConfirmationCount() {
    Product draft = Product.builder().id(PRODUCT_ID).status(ProductStatus.DRAFT).build();

    PublicationStatus status = service().productStatus(draft, 1L);

    assertThat(status.state()).isEqualTo(PublicationState.AWAITING_CONFIRMATIONS);
    assertThat(status.confirmationsReceived()).isEqualTo(1);
    assertThat(status.confirmationsRequired()).isEqualTo(3);
  }

  @Test
  void hiddenProductStatusWinsOverDraft() {
    Product hidden = Product.builder().id(PRODUCT_ID).status(ProductStatus.DRAFT)
        .hiddenAt(OffsetDateTime.now()).build();

    assertThat(service().productStatus(hidden, 0L).state()).isEqualTo(PublicationState.HIDDEN_AFTER_FLAGS);
  }

  @Test
  void activeProductStatusIsPublic() {
    Product active = Product.builder().id(PRODUCT_ID).status(ProductStatus.ACTIVE).build();

    assertThat(service().productStatus(active, 0L).state()).isEqualTo(PublicationState.PUBLIC);
  }

  @Test
  void confirmationsForProductsSkipsNonDraftProducts() {
    Product active = Product.builder().id(PRODUCT_ID).status(ProductStatus.ACTIVE).build();

    assertThat(service().confirmationsForProducts(List.of(active))).isEmpty();
  }

  @Test
  void confirmationsForProductsBatchesDraftIds() {
    Product draft = Product.builder().id(PRODUCT_ID).status(ProductStatus.DRAFT).build();
    when(priceObservationRepository.countDistinctProductContributorsExcludingBatch(List.of(PRODUCT_ID)))
        .thenReturn(List.of(contributorCount(PRODUCT_ID, 2)));

    assertThat(service().confirmationsForProducts(List.of(draft))).containsEntry(PRODUCT_ID, 2L);
  }

  private PriceObservationRepository.ContributorCount contributorCount(long id, long cnt) {
    return new PriceObservationRepository.ContributorCount() {
      @Override
      public Long getId() {
        return id;
      }

      @Override
      public long getCnt() {
        return cnt;
      }
    };
  }
}
