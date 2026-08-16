package cz.kvalitacena.service;

import cz.kvalitacena.config.I18nProperties;
import cz.kvalitacena.controller.UpdateProductInput;
import cz.kvalitacena.controller.UpdateStoreInput;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.Brand;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductUserEdit;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.StoreUserEdit;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductUserEditRepository;
import cz.kvalitacena.db.repo.RetailChainRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.db.repo.StoreUserEditRepository;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Úprava zboží/obchodu jako patch — jen ZMĚNĚNÁ pole se ukládají, shodná hodnota s globálním
 * záznamem patch pole naopak vynuluje (docs/datovy-model.md). Anonym nesmí upravovat vůbec
 * (na rozdíl od submitObservation).
 */
@ExtendWith(MockitoExtension.class)
class CatalogEditServiceTest {

  private static final Long USER_ID = 42L;
  private static final UUID PUBLIC_UID = UUID.randomUUID();
  private static final Long PRODUCT_ID = 7L;
  private static final Long STORE_ID = 9L;
  private static final Long CATEGORY_ID = 3L;

  @Mock
  private ProductRepository productRepository;
  @Mock
  private ProductUserEditRepository productUserEditRepository;
  @Mock
  private CategoryRepository categoryRepository;
  @Mock
  private BrandResolutionService brandResolutionService;
  @Mock
  private ProductOverlayService productOverlayService;
  @Mock
  private StoreRepository storeRepository;
  @Mock
  private StoreUserEditRepository storeUserEditRepository;
  @Mock
  private RetailChainRepository retailChainRepository;
  @Mock
  private StoreOverlayService storeOverlayService;
  @Mock
  private AppUserRepository appUserRepository;
  @Mock
  private TrustLevelService trustLevelService;

  private final CompanyIdValidators companyIdValidators = new CompanyIdValidators(List.of(new IcoValidator()));
  private final I18nProperties i18nProperties = new I18nProperties();

  private CatalogEditService service() {
    i18nProperties.setDefaultCountry("CZ");
    i18nProperties.setCountryCurrency(Map.of("CZ", "CZK", "SK", "EUR", "PL", "PLN"));
    // Messages je null — tenhle test volá jen resolve()/isSupported(), ne supportedCountries().
    CountryResolver countryResolver = new CountryResolver(i18nProperties, appUserRepository, null);
    return new CatalogEditService(productRepository, productUserEditRepository, categoryRepository,
        brandResolutionService, productOverlayService, storeRepository, storeUserEditRepository,
        retailChainRepository, companyIdValidators, storeOverlayService, countryResolver,
        trustLevelService, appUserRepository);
  }

  private Product existingProduct() {
    return Product.builder().id(PRODUCT_ID).name("Mléko polotučné")
        .category(Category.builder().id(CATEGORY_ID).build())
        .unitBase(UnitBase.VOLUME).netContentValue(new BigDecimal("1")).netContentUom(NetContentUom.L)
        .netContentBase(new BigDecimal("1")).build();
  }

  private void givenLoggedInUser() {
    when(appUserRepository.findByPublicUid(PUBLIC_UID)).thenReturn(Optional.of(AppUser.builder().id(USER_ID).build()));
  }

  @Test
  void anonymousCannotUpdateProduct() {
    UpdateProductInput input = new UpdateProductInput("Nový název", null, null, null, null, null,
        null, null, null, null);
    assertThatThrownBy(() -> service().updateProduct(PRODUCT_ID, input, null))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void anonymousCannotUpdateStore() {
    UpdateStoreInput input = new UpdateStoreInput("Nový název", null, null, null, null, null, null,
        null, null, null, null, null, null, null, null);
    assertThatThrownBy(() -> service().updateStore(STORE_ID, input, null))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void changedNameIsStoredInPatch() {
    givenLoggedInUser();
    when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(existingProduct()));
    when(productUserEditRepository.findByProductIdAndUserId(PRODUCT_ID, USER_ID)).thenReturn(Optional.empty());

    UpdateProductInput input = new UpdateProductInput("Mléko plnotučné", null, null, null, null, null,
        null, null, null, null);
    service().updateProduct(PRODUCT_ID, input, PUBLIC_UID);

    ArgumentCaptor<ProductUserEdit> captor = ArgumentCaptor.forClass(ProductUserEdit.class);
    verify(productUserEditRepository).save(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo("Mléko plnotučné");
    assertThat(captor.getValue().getProcessedAt()).isNull();
  }

  @Test
  void nameEqualToGlobalValueIsNotStoredAsPatch() {
    givenLoggedInUser();
    when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(existingProduct()));
    when(productUserEditRepository.findByProductIdAndUserId(PRODUCT_ID, USER_ID)).thenReturn(Optional.empty());

    UpdateProductInput input = new UpdateProductInput("Mléko polotučné", null, null, null, null, null,
        null, null, null, null);
    service().updateProduct(PRODUCT_ID, input, PUBLIC_UID);

    // Patch by byl prázdný (žádné pole se neliší od globálu) — nemá smysl ho ukládat.
    verify(productUserEditRepository, never()).save(any());
    verify(productUserEditRepository, never()).delete(any());
  }

  @Test
  void clearBrandRecordsClearedFieldWhenGlobalHadBrand() {
    givenLoggedInUser();
    Product product = existingProduct();
    product.setBrand(Brand.builder().id(1L).build());
    when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
    when(productUserEditRepository.findByProductIdAndUserId(PRODUCT_ID, USER_ID)).thenReturn(Optional.empty());

    UpdateProductInput input = new UpdateProductInput(null, null, true, null, null, null,
        null, null, null, null);
    service().updateProduct(PRODUCT_ID, input, PUBLIC_UID);

    ArgumentCaptor<ProductUserEdit> captor = ArgumentCaptor.forClass(ProductUserEdit.class);
    verify(productUserEditRepository).save(captor.capture());
    assertThat(captor.getValue().getBrandId()).isNull();
    assertThat(captor.getValue().getClearedFields()).containsExactly("brand");
  }

  @Test
  void emptyEditAfterUpdateDeletesExistingPatch() {
    givenLoggedInUser();
    when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(existingProduct()));
    ProductUserEdit previousEdit = ProductUserEdit.builder().productId(PRODUCT_ID).userId(USER_ID)
        .name("Starý patch název").build();
    when(productUserEditRepository.findByProductIdAndUserId(PRODUCT_ID, USER_ID))
        .thenReturn(Optional.of(previousEdit));

    // Uživatel se vrací na globální název — patch se tím vyprázdní a smaže.
    UpdateProductInput input = new UpdateProductInput("Mléko polotučné", null, null, null, null, null,
        null, null, null, null);
    service().updateProduct(PRODUCT_ID, input, PUBLIC_UID);

    verify(productUserEditRepository).delete(previousEdit);
    verify(productUserEditRepository, never()).save(any());
  }

  @Test
  void invalidIcoIsRejected() {
    givenLoggedInUser();
    when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(Store.builder().id(STORE_ID)
        .name("Obchod").city("Brno").country("CZ").build()));
    when(storeUserEditRepository.findByStoreIdAndUserId(STORE_ID, USER_ID)).thenReturn(Optional.empty());

    UpdateStoreInput input = new UpdateStoreInput(null, null, null, null, null, null, null, null,
        null, "12345678", null, null, null, null, null);
    assertThatThrownBy(() -> service().updateStore(STORE_ID, input, PUBLIC_UID))
        .isInstanceOf(ValidationException.class);
  }

  /**
   * Country na rozdíl od zbytku updateStore obchází store_user_edit a jde přímo do globální
   * entity (docs/lokalizace.md, "Country selector v UI") — má tvrdý dopad na měnu zápisu pro
   * VŠECHNY uživatele, ne jen na to, jak provozovnu vidí autor patche.
   */
  @Test
  void trustedUserChangesStoreCountryDirectlyOnGlobalEntity() {
    givenLoggedInUser();
    when(trustLevelService.isTrusted(any())).thenReturn(true);
    Store store = Store.builder().id(STORE_ID).name("Obchod").city("Bratislava").country("CZ").build();
    when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store));
    when(storeUserEditRepository.findByStoreIdAndUserId(STORE_ID, USER_ID)).thenReturn(Optional.empty());

    UpdateStoreInput input = new UpdateStoreInput(null, null, null, null, null, null, null, null,
        "SK", null, null, null, null, null, null);
    service().updateStore(STORE_ID, input, PUBLIC_UID);

    assertThat(store.getCountry()).isEqualTo("SK");
    verify(storeRepository).save(store);
    // country se NEUKLÁDÁ do patche — je to globální změna, ne pohled jen autora.
    verify(storeUserEditRepository, never()).save(any());
  }

  @Test
  void untrustedUserCannotChangeStoreCountry() {
    givenLoggedInUser();
    when(trustLevelService.isTrusted(any())).thenReturn(false);
    when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(Store.builder().id(STORE_ID)
        .name("Obchod").city("Bratislava").country("CZ").build()));
    when(storeUserEditRepository.findByStoreIdAndUserId(STORE_ID, USER_ID)).thenReturn(Optional.empty());

    UpdateStoreInput input = new UpdateStoreInput(null, null, null, null, null, null, null, null,
        "SK", null, null, null, null, null, null);
    assertThatThrownBy(() -> service().updateStore(STORE_ID, input, PUBLIC_UID))
        .isInstanceOf(ValidationException.class);
    verify(storeRepository, never()).save(any());
  }

  @Test
  void unsupportedStoreCountryIsRejectedRegardlessOfTrust() {
    givenLoggedInUser();
    when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(Store.builder().id(STORE_ID)
        .name("Obchod").city("Brno").country("CZ").build()));
    when(storeUserEditRepository.findByStoreIdAndUserId(STORE_ID, USER_ID)).thenReturn(Optional.empty());

    UpdateStoreInput input = new UpdateStoreInput(null, null, null, null, null, null, null, null,
        "AT", null, null, null, null, null, null);
    assertThatThrownBy(() -> service().updateStore(STORE_ID, input, PUBLIC_UID))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void unchangedCountryDoesNotRequireTrust() {
    givenLoggedInUser();
    when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(Store.builder().id(STORE_ID)
        .name("Obchod").city("Brno").country("CZ").build()));
    when(storeUserEditRepository.findByStoreIdAndUserId(STORE_ID, USER_ID)).thenReturn(Optional.empty());

    // Stejná země jako globální hodnota — nejde o skutečnou změnu, trustLevelService se
    // vůbec nezavolá (nemockovaný isTrusted by jinak vrátil Mockito default false a spadlo by).
    UpdateStoreInput input = new UpdateStoreInput(null, null, null, null, null, null, null, null,
        "CZ", null, null, null, null, null, null);
    service().updateStore(STORE_ID, input, PUBLIC_UID);

    verify(storeRepository, never()).save(any());
  }
}
