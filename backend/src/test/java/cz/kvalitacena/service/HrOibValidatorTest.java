package cz.kvalitacena.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Chorvatský OIB — 11 číslic, ISO 7064 MOD 11-10 (viz {@link HrOibValidator}). */
class HrOibValidatorTest {

  private final HrOibValidator validator = new HrOibValidator();

  @Test
  void acceptsCorrectChecksum() {
    // Kontrolní číslice dopočtená přímo algoritmem MOD 11-10 nad "1234567890".
    assertThat(validator.isValid("12345678903")).isTrue();
  }

  @Test
  void rejectsWrongChecksum() {
    assertThat(validator.isValid("12345678900")).isFalse();
  }

  @Test
  void rejectsWrongLength() {
    assertThat(validator.isValid("1234567890")).isFalse();
    assertThat(validator.isValid("123456789034")).isFalse();
  }

  @Test
  void rejectsNonDigits() {
    assertThat(validator.isValid("1234567890X")).isFalse();
  }

  @Test
  void rejectsNull() {
    assertThat(validator.isValid(null)).isFalse();
  }
}
