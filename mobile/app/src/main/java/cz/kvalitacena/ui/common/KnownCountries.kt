package cz.kvalitacena.ui.common

/**
 * Statický fallback, dokud appka nestáhne `GraphQlClient.countries()` (Query.countries,
 * app.i18n.country-currency na backendu) — appka zatím zná jen CZ/SK/PL (docs/lokalizace.md).
 * Jediné místo, které tenhle seznam hardcoduje na klientovi; server je pořád zdroj pravdy.
 */
val KNOWN_COUNTRIES: Set<String> = setOf("CZ", "SK", "PL")
