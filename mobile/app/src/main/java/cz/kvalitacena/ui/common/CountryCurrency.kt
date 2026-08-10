package cz.kvalitacena.ui.common

/**
 * Zrcadlí `app.i18n.country-currency` na backendu a `frontend/src/app/shared/country-currency.ts`
 * (docs/lokalizace.md) — jen pro NÁPOVĚDU v UI (popisek pole ceny podle vybraného obchodu), samotné
 * rozhodnutí o měně dělá server (`CurrencyResolver.forStore`) nezávisle na tomhle. Drobná neshoda
 * by způsobila jen dočasně špatný popisek pole, nikdy špatně uloženou měnu.
 */
private val COUNTRY_CURRENCY = mapOf("CZ" to "CZK", "SK" to "EUR", "PL" to "PLN")

fun currencyForCountry(country: String?): String = COUNTRY_CURRENCY[country] ?: "CZK"
