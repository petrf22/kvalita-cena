package cz.kvalitacena.service;

import cz.kvalitacena.controller.CatalogDataSource;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductName;
import cz.kvalitacena.db.entity.ProductUserEdit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Výběr názvu podle jazyka klienta (docs/lokalizace.md). Scénář napříč testy je ten, který
 * změnu vyvolal: EAN 8586007690441 (Magnesia) má v OFF český i německý název a hlavní jazyk
 * záznamu 'en'.
 */
class ProductNameResolverTest {

  private final ProductNameResolver resolver = TestI18n.nameResolver();

  @AfterEach
  void resetRequestLocale() {
    LocaleContextHolder.resetLocaleContext();
  }

  private void requestIn(String language) {
    LocaleContextHolder.setLocale(Locale.forLanguageTag(language));
  }

  private OffProduct off() {
    return OffProduct.builder().gtin("08586007690441").lang("en").productName("jemně perlivá")
        .names(Map.of("cs", "jemně perlivá", "de", "Magnesia")).build();
  }

  private Product coreProduct(String name, String lang) {
    return Product.builder().id(1L).name(name).nameLang(lang).build();
  }

  @Test
  void namePicksLanguageOfRequest() {
    requestIn("de");
    ResolvedProductName name = resolver.effective(coreProduct(null, "cs"), off(), null, List.of());

    assertThat(name.name()).isEqualTo("Magnesia");
    assertThat(name.lang()).isEqualTo("de");
    assertThat(name.source()).isEqualTo(CatalogDataSource.OPEN_FOOD_FACTS);
  }

  @Test
  void communityNameWinsOverOffInTheSameLanguage() {
    requestIn("cs");
    Product core = coreProduct(null, "cs");
    ProductName community = ProductName.builder().productId(1L).lang("cs").name("Magnesia jemně perlivá").build();

    ResolvedProductName name = resolver.effective(core, off(), null, List.of(community));

    assertThat(name.name()).isEqualTo("Magnesia jemně perlivá");
    assertThat(name.source()).isEqualTo(CatalogDataSource.COMMUNITY);
  }

  @Test
  void personalPatchWinsButOnlyInItsOwnLanguage() {
    ProductUserEdit edit = ProductUserEdit.builder().productId(1L).userId(2L)
        .name("Moje minerálka").nameLang("cs").build();

    requestIn("cs");
    assertThat(resolver.effective(coreProduct(null, "cs"), off(), edit, List.of()).name())
        .isEqualTo("Moje minerálka");
    requestIn("de");
    assertThat(resolver.effective(coreProduct(null, "cs"), off(), edit, List.of()).name())
        .isEqualTo("Magnesia");
  }

  /** Polština v OFF ani v katalogu není — název ve špatném jazyce je pořád lepší než prázdno. */
  @Test
  void missingLanguageFallsBackToPrimaryNameThenOff() {
    requestIn("pl");
    Product withPrimary = coreProduct("Minerálka", "cs");

    ResolvedProductName primary = resolver.effective(withPrimary, off(), null, List.of());
    assertThat(primary.name()).isEqualTo("Minerálka");
    assertThat(primary.lang()).isEqualTo("cs");

    // Bez vlastního názvu se sáhne na hlavní jazyk OFF ('en'), a když ani ten OFF nemá,
    // na češtinu jako výchozí jazyk appky.
    ResolvedProductName fromOff = resolver.effective(coreProduct(null, "cs"), off(), null, List.of());
    assertThat(fromOff.lang()).isEqualTo("cs");
  }

  /** Starý snapshot bez jazykových variant — jazyk poznat nejde, ale název se ztratit nesmí. */
  @Test
  void legacyOffSnapshotWithoutLanguagesIsLastResort() {
    requestIn("cs");
    OffProduct legacy = OffProduct.builder().gtin("08586007690441").productName("OFF název").build();

    ResolvedProductName name = resolver.effective(coreProduct(null, "cs"), legacy, null, List.of());

    assertThat(name.name()).isEqualTo("OFF název");
    assertThat(name.lang()).isNull();
  }

  @Test
  void allNamesPutRequestLanguageFirst() {
    requestIn("de");
    List<ResolvedProductName> names = resolver.allNames(coreProduct("Minerálka", "cs"), off(), null, List.of());

    assertThat(names).extracting(ResolvedProductName::lang).containsExactly("de", "cs");
    // Komunitní primární název přebil OFF variantu ve stejném jazyce.
    assertThat(names).extracting(ResolvedProductName::name).containsExactly("Magnesia", "Minerálka");
  }
}
