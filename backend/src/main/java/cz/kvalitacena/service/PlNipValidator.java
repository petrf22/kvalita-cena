package cz.kvalitacena.service;

import org.springframework.stereotype.Component;

/**
 * Polský NIP (Numer Identyfikacji Podatkowej) — 10 číslic, kontrolní součet s váhami
 * {@code 6,5,7,2,3,4,5,6,7} nad prvními devíti, mod 11 musí sedět na desátou číslici.
 * Zbytek 10 nemá platnou kontrolní číslici — takové číslo je vždy neplatné.
 */
@Component
public class PlNipValidator implements CompanyIdValidator {

  private static final int[] WEIGHTS = {6, 5, 7, 2, 3, 4, 5, 6, 7};

  @Override
  public String country() {
    return "PL";
  }

  @Override
  public boolean isValid(String value) {
    if (value == null || !value.matches("\\d{10}")) return false;

    int[] digits = value.chars().map(c -> c - '0').toArray();
    int weightedSum = 0;
    for (int i = 0; i < WEIGHTS.length; i++) {
      weightedSum += digits[i] * WEIGHTS[i];
    }
    int checkDigit = weightedSum % 11;
    if (checkDigit == 10) return false;
    return digits[9] == checkDigit;
  }
}
