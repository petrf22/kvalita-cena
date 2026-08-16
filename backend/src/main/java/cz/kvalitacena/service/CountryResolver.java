package cz.kvalitacena.service;

import cz.kvalitacena.config.I18nProperties;
import cz.kvalitacena.controller.CountryInfo;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.repo.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Sjednocené odvození "aktuální země" napříč dotazy, které ji potřebují jako výchozí hodnotu
 * (searchProducts/searchFacets, geocodeAddress, companyByIco) — dřív duplicitně ve
 * StoreGraphQlController i ProductGraphQlController (docs/lokalizace.md, "Country selector v
 * UI"). Pořadí: explicitní argument klienta → auth.app_user.country → app.i18n.default-country,
 * NIKDY "celý svět" bez zadání — jinak by ProductSort.PRICE_ASC řadilo CZK vedle PLN v jednom
 * sloupci.
 */
@Service
@RequiredArgsConstructor
public class CountryResolver {

  private final I18nProperties i18nProperties;
  private final AppUserRepository appUserRepository;

  public String resolve(String explicit, Long viewerUserId) {
    if (explicit != null && !explicit.isBlank()) {
      return explicit;
    }
    if (viewerUserId != null) {
      String userCountry = appUserRepository.findById(viewerUserId).map(AppUser::getCountry).orElse(null);
      if (userCountry != null && !userCountry.isBlank()) {
        return userCountry;
      }
    }
    return i18nProperties.getDefaultCountry();
  }

  /** Appka zná jen země z app.i18n.country-currency — cokoli jiného by CurrencyResolver stejně spadlo na default. */
  public boolean isSupported(String country) {
    return country != null && i18nProperties.getCountryCurrency().containsKey(country);
  }

  /** Číselník pro Query.countries — jeden zdroj pravdy místo hardcoded CZ/SK/PL na klientech. */
  public List<CountryInfo> supportedCountries() {
    return i18nProperties.getCountryCurrency().entrySet().stream()
        .map(e -> new CountryInfo(e.getKey(), e.getValue(), i18nProperties.getCountryLocale().get(e.getKey())))
        .sorted(Comparator.comparing(CountryInfo::code))
        .toList();
  }
}
