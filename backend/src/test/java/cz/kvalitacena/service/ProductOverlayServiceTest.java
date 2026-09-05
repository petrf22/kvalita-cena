package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.CodeType;
import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.OffFetchStatus;
import cz.kvalitacena.db.entity.OffImageKind;
import cz.kvalitacena.db.entity.OffProductImage;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductCode;
import cz.kvalitacena.db.entity.ProductUserEdit;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.db.repo.BrandRepository;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.OffProductRepository;
import cz.kvalitacena.db.repo.ProductCodeRepository;
import cz.kvalitacena.db.repo.ProductNameRepository;
import cz.kvalitacena.db.repo.ProductUserEditRepository;
import org.springframework.context.i18n.LocaleContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductOverlayServiceTest {

  private static final long PRODUCT_ID = 10L;
  private static final long USER_ID = 20L;
  private static final String GTIN = "08594001234578";

  /**
   * Jazyk requestu se v jednotkovém testu nebere z Accept-Language, ale z LocaleContextHolder,
   * který by jinak spadl na locale STROJE — na anglicky nastaveném počítači by pak zápis názvu
   * mířil do jiného jazyka než na českém. Nastavuje se proto explicitně.
   */
  @BeforeEach
  void useCzechRequestLocale() {
    LocaleContextHolder.setLocale(Locale.forLanguageTag("cs"));
  }

  @AfterEach
  void resetRequestLocale() {
    LocaleContextHolder.resetLocaleContext();
  }


  @Mock ProductUserEditRepository editRepository;
  @Mock ProductNameRepository nameRepository;
  @Mock ProductCodeRepository codeRepository;
  @Mock OffProductRepository offRepository;
  @Mock BrandRepository brandRepository;
  @Mock CategoryRepository categoryRepository;

  private ProductOverlayService service() {
    return new ProductOverlayService(editRepository, nameRepository, codeRepository, offRepository,
        brandRepository, categoryRepository, new OffNetContentConverter(),
        TestI18n.nameResolver(), TestI18n.imageResolver());
  }

  private Product coreProduct() {
    return Product.builder().id(PRODUCT_ID).name("Komunitní název")
        .unitBase(UnitBase.COUNT).netContentBase(BigDecimal.ONE).build();
  }

  private void givenOffProduct(Product core) {
    when(codeRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(ProductCode.builder()
        .product(core).code(GTIN).codeType(CodeType.GTIN).primary(true).build()));
    when(offRepository.findById(GTIN)).thenReturn(Optional.of(OffProduct.builder().gtin(GTIN)
        .fetchStatus(OffFetchStatus.FOUND).productName("OFF název").brandName("OFF značka")
        .productQuantity(new BigDecimal("250")).productQuantityUnit("G")
        .mappedCategorySlug("maslo").imageFrontUrl("https://images.openfoodfacts.org/front.jpg").build()));
    when(categoryRepository.findBySlug("maslo"))
        .thenReturn(Optional.of(Category.builder().id(5L).slug("maslo").build()));
  }

  /**
   * Název je od zavedení vícejazyčnosti (docs/lokalizace.md) výjimka z pravidla „OFF přebíjí
   * komunitu": ve stejném jazyce vyhrává komunitní hodnota, protože právě ta vznikla proto,
   * že v OFF nic nebylo. „Hlavní" název z OFF je bez jazykové varianty až poslední záchrana.
   */
  @Test
  void offOverridesCommunityDefaultsButNotTheNameInTheSameLanguage() {
    Product core = coreProduct();
    givenOffProduct(core);

    Product effective = service().applyOverlay(core, null);

    assertThat(effective.getName()).isEqualTo("Komunitní název");
    assertThat(effective.getNameLang()).isEqualTo("cs");
    assertThat(effective.getExternalBrandName()).isEqualTo("OFF značka");
    assertThat(effective.getCategory().getSlug()).isEqualTo("maslo");
    assertThat(effective.getUnitBase()).isEqualTo(UnitBase.MASS);
    assertThat(effective.getNetContentUom()).isEqualTo(NetContentUom.G);
    assertThat(effective.getNetContentBase()).isEqualByComparingTo("0.25");
    assertThat(effective.isOffBacked()).isTrue();
    assertThat(core.getName()).isEqualTo("Komunitní název");
    assertThat(core.getExternalBrandName()).isNull();
  }

  @Test
  void personalPatchOverridesOffDefaults() {
    Product core = coreProduct();
    givenOffProduct(core);
    when(editRepository.findByProductIdAndUserId(PRODUCT_ID, USER_ID)).thenReturn(Optional.of(
        ProductUserEdit.builder().productId(PRODUCT_ID).userId(USER_ID)
            .name("Můj název").nameLang("cs")
            .unitBase("VOLUME").netContentValue(new BigDecimal("1")).netContentUom("L")
            .netContentBase(BigDecimal.ONE).build()));

    Product effective = service().applyOverlay(core, USER_ID);

    assertThat(effective.getName()).isEqualTo("Můj název");
    assertThat(effective.getUnitBase()).isEqualTo(UnitBase.VOLUME);
    assertThat(effective.getNetContentBase()).isEqualByComparingTo("1");
    assertThat(effective.isEditedByMe()).isTrue();
  }

  /**
   * Fotka obalu je vyfocený text stejně jako název — česky mluvící uživatel musí dostat
   * front_cs, ne front_de (docs/lokalizace.md). Data odpovídají reálnému snapshotu
   * EANu 8586007690441 (Magnesia).
   */
  @Test
  void offImageAndNameFollowTheRequestLanguage() {
    Product core = Product.builder().id(PRODUCT_ID).unitBase(UnitBase.COUNT)
        .netContentBase(BigDecimal.ONE).build();
    when(codeRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(ProductCode.builder()
        .product(core).code(GTIN).codeType(CodeType.GTIN).primary(true).build()));
    when(offRepository.findById(GTIN)).thenReturn(Optional.of(OffProduct.builder().gtin(GTIN)
        .fetchStatus(OffFetchStatus.FOUND).lang("en").productName("jemně perlivá")
        .names(Map.of("cs", "jemně perlivá", "de", "Magnesia"))
        .images(List.of(
            OffProductImage.builder().kind(OffImageKind.FRONT).lang("de")
                .url("https://images.openfoodfacts.org/front_de.3.400.jpg").build(),
            OffProductImage.builder().kind(OffImageKind.FRONT).lang("cs")
                .url("https://images.openfoodfacts.org/front_cs.4.400.jpg")
                .smallUrl("https://images.openfoodfacts.org/front_cs.4.200.jpg").build(),
            OffProductImage.builder().kind(OffImageKind.INGREDIENTS).lang("cs")
                .url("https://images.openfoodfacts.org/ingredients_cs.1.400.jpg").build()))
        .build()));

    LocaleContextHolder.setLocale(Locale.forLanguageTag("cs"));
    Product czech = service().applyOverlay(core, null);
    assertThat(czech.getName()).isEqualTo("jemně perlivá");
    assertThat(czech.getOffImageFrontUrl()).endsWith("front_cs.4.400.jpg");
    assertThat(czech.getOffImageLang()).isEqualTo("cs");
    // Etiketa se do "hlavní" fotky nikdy nepromítne, jde jen do seznamu.
    assertThat(czech.getOffImages()).hasSize(3);

    LocaleContextHolder.setLocale(Locale.forLanguageTag("de"));
    Product german = service().applyOverlay(core, null);
    assertThat(german.getName()).isEqualTo("Magnesia");
    assertThat(german.getOffImageFrontUrl()).endsWith("front_de.3.400.jpg");
    assertThat(german.getOffImageLang()).isEqualTo("de");
  }

  /** Snapshot bez selected_images (stažený po staru) nesmí o přední fotku přijít. */
  @Test
  void legacyOffSnapshotStillProvidesTheFrontImage() {
    Product core = coreProduct();
    givenOffProduct(core);

    Product effective = service().applyOverlay(core, null);

    assertThat(effective.getOffImageFrontUrl()).isEqualTo("https://images.openfoodfacts.org/front.jpg");
    assertThat(effective.getOffImageLang()).isNull();
  }
}
