package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.CodeType;
import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.OffFetchStatus;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductCode;
import cz.kvalitacena.db.entity.ProductUserEdit;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.db.repo.BrandRepository;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.OffProductRepository;
import cz.kvalitacena.db.repo.ProductCodeRepository;
import cz.kvalitacena.db.repo.ProductUserEditRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductOverlayServiceTest {

  private static final long PRODUCT_ID = 10L;
  private static final long USER_ID = 20L;
  private static final String GTIN = "08594001234578";

  @Mock ProductUserEditRepository editRepository;
  @Mock ProductCodeRepository codeRepository;
  @Mock OffProductRepository offRepository;
  @Mock BrandRepository brandRepository;
  @Mock CategoryRepository categoryRepository;

  private ProductOverlayService service() {
    return new ProductOverlayService(editRepository, codeRepository, offRepository, brandRepository,
        categoryRepository, new OffNetContentConverter());
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

  @Test
  void offOverridesCommunityDefaultsWithoutMutatingCoreEntity() {
    Product core = coreProduct();
    givenOffProduct(core);

    Product effective = service().applyOverlay(core, null);

    assertThat(effective.getName()).isEqualTo("OFF název");
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
        ProductUserEdit.builder().productId(PRODUCT_ID).userId(USER_ID).name("Můj název")
            .unitBase("VOLUME").netContentValue(new BigDecimal("1")).netContentUom("L")
            .netContentBase(BigDecimal.ONE).build()));

    Product effective = service().applyOverlay(core, USER_ID);

    assertThat(effective.getName()).isEqualTo("Můj název");
    assertThat(effective.getUnitBase()).isEqualTo(UnitBase.VOLUME);
    assertThat(effective.getNetContentBase()).isEqualByComparingTo("1");
    assertThat(effective.isEditedByMe()).isTrue();
  }
}
