package cz.kvalitacena.service;

import org.springframework.stereotype.Component;

/**
 * Italská Partita IVA — 11 číslic, vlastní varianta Luhnova algoritmu (na rozdíl od
 * francouzského SIREN, {@link FrSirenValidator}, se tu zdvojnásobují číslice POČÍTANÉ OD
 * ZAČÁTKU na lichých pozicích, ne od konce, a kontrolní číslice se dopočítává jako doplněk do
 * desítky, ne přímo ze zbytku).
 */
@Component
public class ItPartitaIvaValidator implements CompanyIdValidator {

  @Override
  public String country() {
    return "IT";
  }

  @Override
  public boolean isValid(String value) {
    if (value == null || !value.matches("\\d{11}")) return false;

    int[] digits = value.chars().map(c -> c - '0').toArray();
    int sum = 0;
    for (int i = 0; i < 10; i++) {
      if (i % 2 == 0) {
        sum += digits[i];
      } else {
        int doubled = digits[i] * 2;
        sum += doubled > 9 ? doubled - 9 : doubled;
      }
    }
    int checkDigit = (10 - (sum % 10)) % 10;
    return digits[10] == checkDigit;
  }
}
