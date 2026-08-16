package cz.kvalitacena.service;

import org.springframework.stereotype.Component;

/**
 * Slovinská davčna številka (daňové číslo) — 8 číslic, mod-11 kontrolní součet s klesajícími
 * vahami 8..2 (obdoba {@link IcoValidator}, jiné váhy a jiný zbytek dělá z odvození kontrolní
 * číslice o krok navíc: zbytek 10/11 nemá platnou kontrolní číslici, číslo je pak vždy neplatné).
 */
@Component
public class SiTaxIdValidator implements CompanyIdValidator {

  private static final int[] WEIGHTS = {8, 7, 6, 5, 4, 3, 2};

  @Override
  public String country() {
    return "SI";
  }

  @Override
  public boolean isValid(String value) {
    if (value == null || !value.matches("\\d{8}")) return false;

    int[] digits = value.chars().map(c -> c - '0').toArray();
    int weightedSum = 0;
    for (int i = 0; i < WEIGHTS.length; i++) {
      weightedSum += digits[i] * WEIGHTS[i];
    }
    int remainder = 11 - (weightedSum % 11);
    if (remainder >= 10) return false; // Stejná pojistka jako u polského NIP (PlNipValidator).
    return digits[7] == remainder;
  }
}
