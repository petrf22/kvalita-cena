package cz.kvalitacena.controller;

/**
 * Číselník zemí, které appka zná (app.i18n.country-currency/country-locale) — jeden zdroj
 * pravdy pro klienty, kteří dnes CZ/SK/PL hardcodují na několika místech (docs/lokalizace.md).
 * {@code name} je lokalizovaný podle jazyka aktuálního requestu (messages/countries*.properties,
 * {@code CountryResolver.supportedCountries}) — chybějící klíč pro novou zemi spadne stejně
 * jako u errors/handles, ne že by appka nový kód tiše zobrazila bez názvu.
 */
public record CountryInfo(String code, String currency, String defaultLocale, String name) {
}
