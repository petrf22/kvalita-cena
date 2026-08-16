package cz.kvalitacena.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Slovinská davčna številka — 8 číslic, mod-11 s vahami 8..2 (viz {@link SiTaxIdValidator}). */
class SiTaxIdValidatorTest {

  private final SiTaxIdValidator validator = new SiTaxIdValidator();

  @Test
  void acceptsCorrectChecksum() {
    // 1*8+2*7+3*6+4*5+5*4+6*3+7*2 = 112, 112 % 11 = 2, kontrolní číslice = 11-2 = 9.
    assertThat(validator.isValid("12345679")).isTrue();
  }

  @Test
  void rejectsWrongChecksum() {
    assertThat(validator.isValid("12345670")).isFalse();
  }

  @Test
  void rejectsWrongLength() {
    assertThat(validator.isValid("1234567")).isFalse();
    assertThat(validator.isValid("123456789")).isFalse();
  }

  @Test
  void rejectsNull() {
    assertThat(validator.isValid(null)).isFalse();
  }
}
