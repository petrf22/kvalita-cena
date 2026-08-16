import { Injectable, effect, signal } from '@angular/core';

/**
 * Appka nabízí přepočet jen do měn, které umí stáhnout (ČNB nebo NBS pro RSD), + CZK jako
 * pivot (docs/lokalizace.md) — lehká kopie `app.fx.display-currencies`, rozšířená plánem
 * expanze o 13 dalších zemí (HUF/RON/GBP/CHF/RSD vedle původních EUR/PLN/USD).
 */
export type DisplayCurrency = 'CZK' | 'EUR' | 'PLN' | 'USD' | 'HUF' | 'RON' | 'GBP' | 'CHF' | 'RSD';

export const DISPLAY_CURRENCIES: readonly DisplayCurrency[] = [
  'CZK',
  'EUR',
  'PLN',
  'USD',
  'HUF',
  'RON',
  'GBP',
  'CHF',
  'RSD',
];

const STORAGE_KEY = 'kac.currency';

function isDisplayCurrency(value: string): value is DisplayCurrency {
  return (DISPLAY_CURRENCIES as readonly string[]).includes(value);
}

function readInitialCurrency(): DisplayCurrency | null {
  const stored = localStorage.getItem(STORAGE_KEY);
  return stored && isDisplayCurrency(stored) ? stored : null;
}

/**
 * Zobrazovací měna napříč appkou (docs/lokalizace.md, "Kurzovní lístek a zobrazovací měna") —
 * `null` = "měna obchodu" (výchozí), appka pak nic nepřepočítává a hlavička X-Display-Currency
 * se neposílá vůbec (viz `displayCurrencyInterceptor`). Na rozdíl od {@link
 * import('./language-service').LanguageService} appka nic neukládá na server — je to čistě
 * lokální preference prohlížeče, server o ní neví nic mimo hlavičku jednotlivého requestu.
 */
@Injectable({ providedIn: 'root' })
export class DisplayCurrencyService {
  readonly currency = signal<DisplayCurrency | null>(readInitialCurrency());

  constructor() {
    effect(() => {
      const currency = this.currency();
      if (currency) {
        localStorage.setItem(STORAGE_KEY, currency);
      } else {
        localStorage.removeItem(STORAGE_KEY);
      }
    });
  }

  setCurrency(currency: DisplayCurrency | null): void {
    this.currency.set(currency);
  }
}
