/**
 * Zrcadlí `app.i18n.country-currency` na backendu (docs/lokalizace.md) — jen pro NÁPOVĚDU
 * v UI (popisek "Cena (Kč)" ve formuláři zápisu podle vybraného obchodu), samotné rozhodnutí
 * o měně dělá server (`CurrencyResolver.forStore`) nezávisle na tomhle. Drobná neshoda by
 * způsobila jen dočasně špatný popisek pole, nikdy špatně uloženou měnu.
 */
const COUNTRY_CURRENCY: Record<string, string> = {
  CZ: 'CZK',
  SK: 'EUR',
  PL: 'PLN',
  DE: 'EUR',
  AT: 'EUR',
  FR: 'EUR',
  ES: 'EUR',
  IT: 'EUR',
  HR: 'EUR',
  SI: 'EUR',
  BG: 'EUR',
  HU: 'HUF',
  RO: 'RON',
  GB: 'GBP',
  CH: 'CHF',
  RS: 'RSD',
};

export function currencyForCountry(country: string | null | undefined): string {
  return (country && COUNTRY_CURRENCY[country]) || 'CZK';
}
