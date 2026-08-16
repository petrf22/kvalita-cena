/**
 * Statický fallback, než appka stáhne CountryService.countries() (Query.countries,
 * app.i18n.country-currency na backendu) — plán expanze o 13 dalších zemí (docs/lokalizace.md).
 * Jediné místo, které tenhle seznam hardcoduje na klientovi; server je pořád zdroj pravdy.
 */
export const KNOWN_COUNTRIES: readonly string[] = [
  'CZ',
  'SK',
  'PL',
  'DE',
  'AT',
  'FR',
  'ES',
  'IT',
  'HR',
  'SI',
  'BG',
  'HU',
  'RO',
  'GB',
  'CH',
  'RS',
];
