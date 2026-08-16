package cz.kvalitacena.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Italská Partita IVA — 11 číslic, italská varianta Luhna (viz {@link ItPartitaIvaValidator}). */
class ItPartitaIvaValidatorTest {

  private final ItPartitaIvaValidator validator = new ItPartitaIvaValidator();

  @Test
  void acceptsCorrectChecksum() {
    assertThat(validator.isValid("12345678903")).isTrue();
  }

  @Test
  void rejectsWrongChecksum() {
    assertThat(validator.isValid("12345678904")).isFalse();
  }

  @Test
  void rejectsWrongLength() {
    assertThat(validator.isValid("1234567890")).isFalse();
    assertThat(validator.isValid("1234567890344")).isFalse();
  }

  @Test
  void rejectsNull() {
    assertThat(validator.isValid(null)).isFalse();
  }
}
