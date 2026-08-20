import { Injectable, effect, inject, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import {
  NzI18nInterface,
  NzI18nService,
  cs_CZ,
  de_DE,
  en_GB,
  pl_PL,
  sk_SK,
} from 'ng-zorro-antd/i18n';
import { firstValueFrom } from 'rxjs';
import { AuthService } from './auth-service';
import { ViewerService } from './viewer-service';

export type AppLang = 'cs' | 'sk' | 'en' | 'pl' | 'de';

/** Jediné místo, které zná seznam podporovaných jazyků — přidání dalšího je jeden řádek sem. */
export const AVAILABLE_LANGS: readonly AppLang[] = ['cs', 'sk', 'en', 'pl', 'de'];

// Locale objekty jednotlivých jazykových balíčků ng-zorro-antd mají mírně odlišný tvar (novější
// komponenty přibývají do různých jazyků postupně) — NzI18nInterface je společný průnik, který
// NzI18nService.setLocale skutečně očekává.
const NZ_LOCALES: Record<AppLang, NzI18nInterface> = {
  cs: cs_CZ,
  sk: sk_SK,
  en: en_GB,
  pl: pl_PL,
  de: de_DE,
};

/** Pro Intl.* formátování (FormatService, relative-date) — ne totéž co Angular LOCALE_ID. */
export const INTL_TAGS: Record<AppLang, string> = {
  cs: 'cs-CZ',
  sk: 'sk-SK',
  en: 'en-GB',
  pl: 'pl-PL',
  de: 'de-DE',
};

// Pro `nz-date-picker` (FormatService.dateInputFormat) — `NzNativeDateAdapter` formátuje přes
// date-fns tokeny, ne Intl, takže nejde odvodit ze stejného zdroje jako INTL_TAGS.
export const DATE_INPUT_FORMATS: Record<AppLang, string> = {
  cs: 'dd.MM.yyyy',
  sk: 'dd.MM.yyyy',
  en: 'dd/MM/yyyy',
  pl: 'dd.MM.yyyy',
  de: 'dd.MM.yyyy',
};

const STORAGE_KEY = 'kac.lang';

function isAppLang(value: string): value is AppLang {
  return (AVAILABLE_LANGS as readonly string[]).includes(value);
}

/** localStorage → navigator.languages ∩ podporované → čeština (docs/lokalizace.md, cs = zdroj i fallback). */
export function readInitialLang(): AppLang {
  const stored = localStorage.getItem(STORAGE_KEY);
  if (stored && isAppLang(stored)) return stored;

  for (const tag of navigator.languages ?? [navigator.language]) {
    const short = tag.slice(0, 2).toLowerCase();
    if (isAppLang(short)) return short;
  }
  return 'cs';
}

/**
 * Runtime přepínání jazyka — jádro fáze 2 (docs/lokalizace.md). `LOCALE_ID` a `provideNzI18n`
 * jsou Angular providery vyhodnocené JEDNOU při bootstrapu, takže uživatelsky viditelné
 * formátování jde přes {@link FormatService} (Intl.* podle signálu {@link lang}), ne přes
 * `CurrencyPipe`/`DatePipe` — a měna navíc přichází z dat (multi-currency), ne z locale.
 * ng-zorro se PŘEPÍNAT dá (`NzI18nService.setLocale`), takže ten efekt jen zavolá.
 */
@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly transloco = inject(TranslocoService);
  private readonly nzI18n = inject(NzI18nService);
  private readonly auth = inject(AuthService);
  private readonly viewerService = inject(ViewerService);

  readonly lang = signal<AppLang>(readInitialLang());

  constructor() {
    effect(() => {
      const lang = this.lang();
      this.transloco.setActiveLang(lang);
      this.nzI18n.setLocale(NZ_LOCALES[lang]);
      document.documentElement.lang = lang;
      localStorage.setItem(STORAGE_KEY, lang);
    });
  }

  /**
   * Nejdřív se dotáhne bundle nového jazyka, AŽ POTOM se přepne signál — jinak by UI na zlomek
   * vteřiny spadlo do fallbacku/prázdna, než HTTP dotaz doběhne.
   */
  async setLang(lang: AppLang): Promise<void> {
    if (lang === this.lang()) return;
    await firstValueFrom(this.transloco.load(lang));
    this.lang.set(lang);
    // Volba klienta je autoritativní — posílá se na server jen kvůli asynchronnímu výstupu
    // (OTP e-mail), ne aby o ní server rozhodoval (docs/lokalizace.md). Selhání requestu appku
    // nesmí zablokovat, jazyk webu se přepnul bez ohledu na to, jestli se to uložilo na server.
    if (this.auth.isLoggedIn()) {
      this.viewerService.setLocale(lang).subscribe({ error: () => undefined });
    }
  }
}
