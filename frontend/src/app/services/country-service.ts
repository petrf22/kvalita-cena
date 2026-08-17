import { Injectable, effect, inject, signal } from '@angular/core';
import { map } from 'rxjs';
import { AuthService } from './auth-service';
import { GraphQlService } from './graphql-service';
import { LanguageService, readInitialLang } from './language-service';
import { ViewerService } from './viewer-service';
import { graphql } from '../models/generated';

export interface CountryOption {
  code: string;
  currency: string;
  defaultLocale: string | null;
}

const STORAGE_KEY = 'kac.country';

/** Jen výchozí HÁDANKA, než appka stáhne countries() nebo než si uživatel zemi sám nastaví — nikdy zdroj pravdy. */
const LANG_TO_COUNTRY: Record<string, string> = { cs: 'CZ', sk: 'SK', pl: 'PL', en: 'CZ', de: 'DE' };

function readInitialCountry(): string {
  return localStorage.getItem(STORAGE_KEY) ?? LANG_TO_COUNTRY[readInitialLang()] ?? 'CZ';
}

/**
 * Přepínač země nezávislý na jazyku (docs/lokalizace.md, "Country selector v UI") — stejný vzor
 * jako {@link LanguageService}: preference je vždy autoritativně na klientovi (localStorage),
 * appka se z ní NIKDY nestahuje zpátky ze serveru (motivační případ dokumentu: Čech žijící
 * v Polsku chce české UI a polské ceny, tj. jazyk a země se musí umět rozejít). Přihlášeným se
 * navíc posílá na server přes {@code setLocale} — `auth.app_user.country` slouží výhradně
 * asynchronnímu výstupu (OTP e-mail), appka z něj nikdy nerozhoduje o obsahu odpovědi.
 *
 * Ovlivňuje: výchozí zemi/měnu formuláře zakládání obchodu (store-form), filtr `country`
 * v hledání (search-page) a popisek "Cena (Kč/€/zł)" u obchodů, u kterých appka ještě nezná
 * `store.country` (nový obchod v modalu). NEMĚNÍ měnu už zapsaných cen ani existujících
 * obchodů — ta je vlastností konkrétní provozovny, ne vieweru (CurrencyResolver.forStore).
 */
@Injectable({ providedIn: 'root' })
export class CountryService {
  private readonly graphQl = inject(GraphQlService);
  private readonly auth = inject(AuthService);
  private readonly languageService = inject(LanguageService);
  private readonly viewerService = inject(ViewerService);

  readonly country = signal<string>(readInitialCountry());
  /** Číselník ze serveru (app.i18n.country-currency/country-locale) — prázdné, dokud dotaz nedoběhne. */
  readonly countries = signal<CountryOption[]>([]);

  constructor() {
    effect(() => localStorage.setItem(STORAGE_KEY, this.country()));
    this.loadCountries();
  }

  setCountry(country: string): void {
    if (country === this.country()) return;
    this.country.set(country);
    if (this.auth.isLoggedIn()) {
      this.viewerService
        .setLocale(this.languageService.lang(), country)
        .subscribe({ error: () => undefined });
    }
  }

  private loadCountries(): void {
    const document = graphql(`
      query Countries {
        countries {
          code
          currency
          defaultLocale
        }
      }
    `);
    this.graphQl
      .execute(document)
      .pipe(map((data) => data.countries))
      .subscribe({
        next: (list) => this.countries.set(list),
        // Číselník je jen doplněk (select options, currencyForCountry fallback zůstává
        // statický) — výpadek dotazu appku nesmí zablokovat.
        error: () => {},
      });
  }
}
