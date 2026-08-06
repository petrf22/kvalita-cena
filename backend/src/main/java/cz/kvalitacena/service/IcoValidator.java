package cz.kvalitacena.service;

import org.springframework.stereotype.Component;

/**
 * Kontrolní součet českého IČO (8 číslic, modulo 11) — viz např. zákon o základních registrech.
 * Validuje jen TVAR čísla, ne že firma reálně existuje; existenci ověřuje až AresService
 * (companyByIco), volitelně a asynchronně vůči uložení obchodu.
 */
@Component
public class IcoValidator {

  /** @return true, pokud řetězec vypadá jako syntakticky platné IČO (8 číslic + správný kontrolní součet). */
  public boolean isValid(String ico) {
    if (ico == null || !ico.matches("\\d{8}")) return false;

    int[] digits = ico.chars().map(c -> c - '0').toArray();
    int weightedSum = 0;
    for (int i = 0; i < 7; i++) {
      weightedSum += digits[i] * (8 - i);
    }
    int remainder = weightedSum % 11;
    int checkDigit = switch (remainder) {
      case 0 -> 1;
      case 1 -> 0;
      default -> 11 - remainder;
    };
    return digits[7] == checkDigit;
  }
}
