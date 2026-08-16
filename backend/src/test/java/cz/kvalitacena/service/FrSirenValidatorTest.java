package cz.kvalitacena.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Francouzský SIREN — 9 číslic, standardní Luhn (viz {@link FrSirenValidator}). */
class FrSirenValidatorTest {

  private final FrSirenValidator validator = new FrSirenValidator();

  @Test
  void acceptsCorrectChecksum() {
    assertThat(validator.isValid("732829320")).isTrue();
  }

  @Test
  void rejectsWrongChecksum() {
    assertThat(validator.isValid("732829321")).isFalse();
  }

  @Test
  void rejectsWrongLength() {
    assertThat(validator.isValid("73282932")).isFalse();
    assertThat(validator.isValid("7328293200")).isFalse();
  }

  @Test
  void rejectsNull() {
    assertThat(validator.isValid(null)).isFalse();
  }
}
