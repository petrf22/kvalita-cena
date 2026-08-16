package cz.kvalitacena.controller;

/**
 * Číselník zemí, které appka zná (app.i18n.country-currency/country-locale) — jeden zdroj
 * pravdy pro klienty, kteří dnes CZ/SK/PL hardcodují na několika místech (docs/lokalizace.md).
 */
public record CountryInfo(String code, String currency, String defaultLocale) {
}
