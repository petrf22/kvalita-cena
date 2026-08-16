package cz.kvalitacena.ui.common

/**
 * Statický fallback, dokud appka nestáhne `GraphQlClient.countries()` (Query.countries,
 * app.i18n.country-currency na backendu) — plán expanze o 13 dalších zemí (docs/lokalizace.md).
 * `StoreFormScreen.CountryDropdown` ho na rozdíl od nastavení používá jako jediný zdroj, ne jen
 * fallback — server je pořád zdroj pravdy pro Query.countries, tenhle seznam je jeho kopie.
 */
val KNOWN_COUNTRIES: Set<String> = setOf(
  "CZ", "SK", "PL", "DE", "AT", "FR", "ES", "IT", "HR", "SI", "BG", "HU", "RO", "GB", "CH", "RS",
)
