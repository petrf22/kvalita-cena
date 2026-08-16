package cz.kvalitacena.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Srbský PIB — 9 číslic, stejný ISO 7064 MOD 11-10 jako {@link HrOibValidator}, kratší číslo. */
class RsPibValidatorTest {

  private final RsPibValidator validator = new RsPibValidator();

  @Test
  void acceptsCorrectChecksum() {
    // Kontrolní číslice dopočtená přímo algoritmem MOD 11-10 nad "12345678".
    assertThat(validator.isValid("123456788")).isTrue();
  }

  @Test
  void rejectsWrongChecksum() {
    assertThat(validator.isValid("123456780")).isFalse();
  }

  @Test
  void rejectsWrongLength() {
    assertThat(validator.isValid("1234567")).isFalse();
    assertThat(validator.isValid("1234567889")).isFalse();
  }

  @Test
  void rejectsNull() {
    assertThat(validator.isValid(null)).isFalse();
  }
}
