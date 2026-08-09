package cz.kvalitacena.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Vybere registr podle země — obdoba {@link CompanyIdValidators}. */
@Component
@RequiredArgsConstructor
public class CompanyRegistries {

  private final List<CompanyRegistry> registries;

  public Optional<CompanyRegistry> forCountry(String country) {
    if (country == null) return Optional.empty();
    return registries.stream().filter(r -> r.country().equalsIgnoreCase(country)).findFirst();
  }
}
