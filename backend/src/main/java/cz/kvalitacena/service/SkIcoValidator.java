package cz.kvalitacena.service;

import org.springframework.stereotype.Component;

/** Slovenské IČO — stejný mod-11 algoritmus jako české (společné dědictví ČSFR), viz {@link IcoValidator}. */
@Component
public class SkIcoValidator implements CompanyIdValidator {

  @Override
  public String country() {
    return "SK";
  }

  @Override
  public boolean isValid(String value) {
    return IcoValidator.checksumValid(value);
  }
}
