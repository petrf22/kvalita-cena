package cz.kvalitacena.service;

import org.springframework.stereotype.Component;

/**
 * Francouzský SIREN (Système d'identification du répertoire des entreprises) — 9 číslic,
 * standardní Luhnův algoritmus (stejný jako u platebních karet), ne mod-11 jako CZ/SK/SI.
 */
@Component
public class FrSirenValidator implements CompanyIdValidator {

  @Override
  public String country() {
    return "FR";
  }

  @Override
  public boolean isValid(String value) {
    if (value == null || !value.matches("\\d{9}")) return false;

    int[] digits = value.chars().map(c -> c - '0').toArray();
    int sum = 0;
    // Luhn zdvojnásobuje číslice počítáno OD KONCE, poslední (kontrolní) číslice se
    // nezdvojnásobuje — u 9místného čísla (index 0..8, poslední index 8 sudý) to vychází na
    // liché indexy od začátku (1,3,5,7).
    for (int i = 0; i < digits.length; i++) {
      int digit = digits[i];
      if (i % 2 == 1) {
        digit *= 2;
        if (digit > 9) digit -= 9;
      }
      sum += digit;
    }
    return sum % 10 == 0;
  }
}
