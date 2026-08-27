package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.entity.UnitBase;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OffCatalogMappingTest {

  @Test
  void mostSpecificCategoryWins() {
    assertThat(new OffCategoryMapper().categorySlugFor(List.of("en:foods", "en:dairies", "en:butters")))
        .isEqualTo("maslo");
  }

  @Test
  void unknownCategoryNeedsManualSelection() {
    assertThat(new OffCategoryMapper().categorySlugFor(List.of("en:unknown-category"))).isNull();
  }

  @Test
  void gramsAreConvertedToMassBase() {
    OffProduct product = OffProduct.builder().productQuantity(new BigDecimal("250"))
        .productQuantityUnit("G").build();

    OffNetContent result = new OffNetContentConverter().convert(product);

    assertThat(result.unitBase()).isEqualTo(UnitBase.MASS);
    assertThat(result.uom()).isEqualTo(NetContentUom.G);
    assertThat(result.base()).isEqualByComparingTo("0.25");
  }

  @Test
  void unsupportedQuantityIsIgnored() {
    OffProduct product = OffProduct.builder().productQuantity(new BigDecimal("3"))
        .productQuantityUnit("PCS").build();
    assertThat(new OffNetContentConverter().convert(product)).isNull();
  }
}
