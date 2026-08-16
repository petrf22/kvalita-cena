/**
 * Statický fallback, než appka stáhne CountryService.countries() (Query.countries,
 * app.i18n.country-currency na backendu) — appka zatím zná jen CZ/SK/PL (docs/lokalizace.md).
 * Jediné místo, které tenhle seznam hardcoduje na klientovi; server je pořád zdroj pravdy.
 */
export const KNOWN_COUNTRIES: readonly string[] = ['CZ', 'SK', 'PL'];
