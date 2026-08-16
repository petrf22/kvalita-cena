package cz.kvalitacena.service;

import org.springframework.stereotype.Component;

/**
 * Srbský PIB (Poreski identifikacioni broj) — 9 číslic, stejný ISO 7064 MOD 11-10 kontrolní
 * součet jako chorvatský OIB ({@link HrOibValidator}), jen nad kratším číslem.
 */
@Component
public class RsPibValidator implements CompanyIdValidator {

  @Override
  public String country() {
    return "RS";
  }

  @Override
  public boolean isValid(String value) {
    return HrOibValidator.checksumValid(value, 9);
  }
}
