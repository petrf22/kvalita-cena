package cz.kvalitacena.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * České IČO — 8 číslic, kontrolní součet modulo 11 na prvních 7 z nich. Validuje se jen tvar
 * čísla; existenci firmy ověřuje až AresService (companyByIco), volitelně.
 */
class IcoValidatorTest {

  private final IcoValidator validator = new IcoValidator();

  @Test
  void acceptsCorrectChecksum() {
    // 1*8+2*7+3*6+4*5+5*4+6*3+7*2 = 112, 112 % 11 = 2, kontrolní číslice = 11-2 = 9.
    assertThat(validator.isValid("12345679")).isTrue();
  }

  @Test
  void rejectsWrongChecksum() {
    assertThat(validator.isValid("12345678")).isFalse();
  }

  @Test
  void acceptsChecksumWhenRemainderIsZero() {
    // 2*8+5*7+5*6+9*5+6*4+6*3+4*2 = 176, 176 % 11 = 0 → kontrolní číslice = 1.
    assertThat(validator.isValid("25596641")).isTrue();
  }

  @Test
  void rejectsWrongLength() {
    assertThat(validator.isValid("1234567")).isFalse();
    assertThat(validator.isValid("123456789")).isFalse();
  }

  @Test
  void rejectsNonDigits() {
    assertThat(validator.isValid("1234567X")).isFalse();
  }

  @Test
  void rejectsNull() {
    assertThat(validator.isValid(null)).isFalse();
  }
}
