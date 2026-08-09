package cz.kvalitacena.service;

import org.springframework.stereotype.Component;

/**
 * Kontrolní součet IČO (8 číslic, modulo 11) — viz např. zákon o základních registrech. Stejný
 * algoritmus platí i pro slovenské IČO (společné dědictví ČSFR), viz {@link SkIcoValidator}.
 * Validuje jen TVAR čísla, ne že firma reálně existuje; existenci ověřuje až AresService
 * (companyByIco), volitelně a asynchronně vůči uložení obchodu.
 */
@Component
public class IcoValidator implements CompanyIdValidator {

  @Override
  public String country() {
    return "CZ";
  }

  /** @return true, pokud řetězec vypadá jako syntakticky platné IČO (8 číslic + správný kontrolní součet). */
  @Override
  public boolean isValid(String value) {
    return checksumValid(value);
  }

  /** Sdílené se {@link SkIcoValidator} — stejný mod-11 algoritmus nad 8 číslicemi. */
  static boolean checksumValid(String ico) {
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
