package cz.kvalitacena.service;

import cz.kvalitacena.config.I18nProperties;
import cz.kvalitacena.db.entity.Store;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Měna ceny je vlastnost PROVOZOVNY, ne přání zapisujícího (docs/lokalizace.md) — kdyby ji
 * posílal jen klient bez validace, dřív nebo později skončí "Kč" u polského Lidlu.
 */
@Service
@RequiredArgsConstructor
public class CurrencyResolver {

  private final I18nProperties i18nProperties;

  public String forStore(Store store) {
    String country = store.getCountry();
    String currency = country == null ? null : i18nProperties.getCountryCurrency().get(country);
    return currency != null ? currency : defaultCurrency();
  }

  public boolean isSupported(String currency) {
    return currency != null && i18nProperties.getCountryCurrency().containsValue(currency);
  }

  /** Měna app.i18n.default-country — použije se, když nejde odvodit z konkrétní provozovny/dat. */
  public String defaultCurrency() {
    return i18nProperties.getCountryCurrency()
        .getOrDefault(i18nProperties.getDefaultCountry(), "CZK");
  }
}
