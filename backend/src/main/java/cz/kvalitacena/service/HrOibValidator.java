package cz.kvalitacena.service;

import org.springframework.stereotype.Component;

/**
 * Chorvatský OIB (Osobni identifikacijski broj) — 11 číslic, kontrolní součet ISO 7064
 * MOD 11-10 (stejný systém jako srbský PIB, viz {@link RsPibValidator}, jen nad 10 číslicemi
 * místo 8). Validuje jen TVAR, ne existenci — viz {@link IcoValidator} pro stejnou poznámku
 * u českého IČO.
 */
@Component
public class HrOibValidator implements CompanyIdValidator {

  @Override
  public String country() {
    return "HR";
  }

  @Override
  public boolean isValid(String value) {
    return checksumValid(value, 11);
  }

  /**
   * ISO 7064 MOD 11-10 nad prvními {@code length - 1} číslicemi, poslední číslice je kontrolní
   * — sdílené s {@link RsPibValidator} (délka je jediný rozdíl mezi OIB a PIB).
   */
  static boolean checksumValid(String value, int length) {
    if (value == null || !value.matches("\\d{" + length + "}")) return false;

    int[] digits = value.chars().map(c -> c - '0').toArray();
    int remainder = 10;
    for (int i = 0; i < length - 1; i++) {
      remainder = (remainder + digits[i]) % 10;
      if (remainder == 0) remainder = 10;
      remainder = (remainder * 2) % 11;
    }
    int checkDigit = (11 - remainder) % 10;
    return digits[length - 1] == checkDigit;
  }
}
