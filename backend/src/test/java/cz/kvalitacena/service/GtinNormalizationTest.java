package cz.kvalitacena.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** EAN pro zápis do katalogu — kontrolní číslice a omezený oběh (GS1). */
class GtinNormalizationTest {

  @ParameterizedTest
  @ValueSource(strings = {"8594001740659", "90169588", "011210617001", "08594001740659", " 8594001740659 "})
  void globalniEanProjde(String code) {
    assertThat(GtinNormalization.isGlobalGtin(code)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "5997321796297",   // OCR spletlo jednu číslici (správně 5997321736297)
      "400990052165",    // OCR vynechalo číslici
      "859400174065",    // 12 číslic, kontrolní nesedí
      "2012345678903",   // etiketa váhového zboží (prefix 2) — nese cenu, ne zboží
      "0412345678903",   // GTIN-13 prefix 04, omezený oběh
      "9812345678902",   // kupón (98)
      "20123451",        // EAN-8 s prefixem 2
      "123456789",       // 9 číslic neexistuje
      "", "abc"})
  void neplatnyNeboVnitroobchodniKodNeprojde(String code) {
    assertThat(GtinNormalization.isGlobalGtin(code)).isFalse();
  }

  @Test
  void nullNeprojde() {
    assertThat(GtinNormalization.isGlobalGtin(null)).isFalse();
  }
}
