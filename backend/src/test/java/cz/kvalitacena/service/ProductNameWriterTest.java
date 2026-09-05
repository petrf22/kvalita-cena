package cz.kvalitacena.service;

import cz.kvalitacena.controller.ProductNameInput;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductName;
import cz.kvalitacena.db.entity.ProductUserEdit;
import cz.kvalitacena.db.repo.ProductNameRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dělící čára mezi globálním zápisem a osobním patchem (docs/lokalizace.md): doplnění jazyka,
 * který zboží nemá, je pro všechny; přepsání existující hodnoty jen pro autora.
 */
@ExtendWith(MockitoExtension.class)
class ProductNameWriterTest {

  private static final Long PRODUCT_ID = 1L;

  @Mock private ProductNameRepository productNameRepository;

  private ProductNameWriter writer() {
    return new ProductNameWriter(productNameRepository, TestI18n.properties());
  }

  private final AppUser user = AppUser.builder().id(42L).build();

  private OffProduct germanOnly() {
    return OffProduct.builder().gtin("08586007690441").lang("de").productName("Magnesia")
        .names(Map.of("de", "Magnesia")).build();
  }

  /** Zboží založené nad OFF snapshotem má core.product.name schválně NULL (ODbL). */
  private Product offBackedProduct() {
    return Product.builder().id(PRODUCT_ID).name(null).nameLang("cs").build();
  }

  @Test
  void missingLanguageGoesToTheGlobalPrimaryNameWhenItIsStillEmpty() {
    when(productNameRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of());
    Product product = offBackedProduct();
    ProductUserEdit edit = ProductUserEdit.builder().productId(PRODUCT_ID).userId(42L).build();

    writer().apply(product, germanOnly(), edit, "Magnesia jemně perlivá", "cs", null, user);

    assertThat(product.getName()).isEqualTo("Magnesia jemně perlivá");
    assertThat(edit.getName()).isNull();
    // Primární jazyk patří do core.product.name, ne do core.product_name — jinak by byl
    // název ve dvou tabulkách naráz.
    verify(productNameRepository, never()).save(any());
  }

  @Test
  void missingLanguageOtherThanPrimaryGoesToProductNameTable() {
    when(productNameRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of());
    Product product = Product.builder().id(PRODUCT_ID).name("Minerálka").nameLang("cs").build();
    ProductUserEdit edit = ProductUserEdit.builder().productId(PRODUCT_ID).userId(42L).build();

    writer().apply(product, null, edit, null, "cs",
        List.of(new ProductNameInput("pl", "Woda mineralna")), user);

    ArgumentCaptor<ProductName> captor = ArgumentCaptor.forClass(ProductName.class);
    verify(productNameRepository).save(captor.capture());
    assertThat(captor.getValue().getLang()).isEqualTo("pl");
    assertThat(captor.getValue().getName()).isEqualTo("Woda mineralna");
    assertThat(captor.getValue().getCreatedByUserId()).isEqualTo(42L);
    assertThat(edit.getName()).isNull();
  }

  @Test
  void changingAnExistingNameStaysPersonal() {
    when(productNameRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of());
    Product product = offBackedProduct();
    ProductUserEdit edit = ProductUserEdit.builder().productId(PRODUCT_ID).userId(42L).build();

    writer().apply(product, germanOnly(), edit, "Magnesia Mineralwasser", "de", null, user);

    assertThat(edit.getName()).isEqualTo("Magnesia Mineralwasser");
    assertThat(edit.getNameLang()).isEqualTo("de");
    assertThat(product.getName()).isNull();
    verify(productNameRepository, never()).save(any());
  }

  @Test
  void confirmingTheGlobalValueClearsThePatch() {
    when(productNameRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of());
    ProductUserEdit edit = ProductUserEdit.builder().productId(PRODUCT_ID).userId(42L)
        .name("Magnesia Mineralwasser").nameLang("de").build();

    writer().apply(offBackedProduct(), germanOnly(), edit, "Magnesia", "de", null, user);

    assertThat(edit.getName()).isNull();
    assertThat(edit.getNameLang()).isNull();
  }

  @Test
  void twoOverwrittenLanguagesAtOnceAreRejected() {
    when(productNameRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of());
    Product product = Product.builder().id(PRODUCT_ID).name("Minerálka").nameLang("cs").build();
    ProductUserEdit edit = ProductUserEdit.builder().productId(PRODUCT_ID).userId(42L).build();

    assertThatThrownBy(() -> writer().apply(product, germanOnly(), edit, "Jiná minerálka", "cs",
        List.of(new ProductNameInput("de", "Anderes Wasser")), user))
        .isInstanceOf(ValidationException.class)
        .extracting("code").isEqualTo(ErrorCode.PRODUCT_NAME_EDIT_SINGLE_LANG);
  }

  @Test
  void unsupportedLanguageIsRejectedOnWriteNotSilentlyStored() {
    Product product = Product.builder().id(PRODUCT_ID).name("Minerálka").nameLang("cs").build();
    ProductUserEdit edit = ProductUserEdit.builder().productId(PRODUCT_ID).userId(42L).build();

    assertThatThrownBy(() -> writer().apply(product, null, edit, null, "cs",
        List.of(new ProductNameInput("fr", "Eau minérale")), user))
        .isInstanceOf(ValidationException.class)
        .extracting("code").isEqualTo(ErrorCode.PRODUCT_NAME_LANG_UNSUPPORTED);
  }

  @Test
  void sameLanguageTwiceIsRejected() {
    Product product = Product.builder().id(PRODUCT_ID).name("Minerálka").nameLang("cs").build();
    ProductUserEdit edit = ProductUserEdit.builder().productId(PRODUCT_ID).userId(42L).build();

    assertThatThrownBy(() -> writer().apply(product, null, edit, "Minerálka", "cs",
        List.of(new ProductNameInput("cs", "Minerálka jiná")), user))
        .isInstanceOf(ValidationException.class)
        .extracting("code").isEqualTo(ErrorCode.PRODUCT_NAME_LANG_DUPLICATE);
  }

  @Test
  void primaryLangFallsBackFromRequestToDefaultLocale() {
    assertThat(writer().primaryLang("de", "cs")).isEqualTo("de");
    assertThat(writer().primaryLang(null, "pl")).isEqualTo("pl");
    // Jazyk, který appka neumí, se nikdy neuloží — spadne se na výchozí jazyk.
    assertThat(writer().primaryLang(null, "fr")).isEqualTo("cs");
  }
}
