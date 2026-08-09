package cz.kvalitacena.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Vybere validátor podle země obchodu (docs/lokalizace.md). Pro zemi bez validátoru se hodnota
 * IČO/NIP uloží BEZ kontroly — je to lepší než ji odmítnout, appka o algoritmu té země prostě
 * ještě neví.
 */
@Component
@RequiredArgsConstructor
public class CompanyIdValidators {

  private final List<CompanyIdValidator> validators;

  public Optional<CompanyIdValidator> forCountry(String country) {
    if (country == null) return Optional.empty();
    return validators.stream().filter(v -> v.country().equalsIgnoreCase(country)).findFirst();
  }
}
