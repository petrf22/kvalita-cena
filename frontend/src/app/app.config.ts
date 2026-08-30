import { registerLocaleData } from '@angular/common';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import cs from '@angular/common/locales/cs';
import de from '@angular/common/locales/de';
import en from '@angular/common/locales/en';
import pl from '@angular/common/locales/pl';
import sk from '@angular/common/locales/sk';
import {
  ApplicationConfig,
  LOCALE_ID,
  isDevMode,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideTranslocoMessageformat } from '@jsverse/transloco-messageformat';
import { provideTransloco } from '@jsverse/transloco';
import { provideNzNativeDateAdapter } from 'ng-zorro-antd/core/time';
import { cs_CZ, provideNzI18n } from 'ng-zorro-antd/i18n';
import { provideNzIcons } from 'ng-zorro-antd/icon';

import { routes } from './app.routes';
import { authInitializer } from './func/auth-initializer';
import { displayCurrencyInterceptor } from './func/display-currency-interceptor';
import { languageInitializer } from './func/language-initializer';
import { languageInterceptor } from './func/language-interceptor';
import { navigationHistoryInitializer } from './func/navigation-history-initializer';
import { tokenInterceptor } from './func/token-interceptor';
import { icons } from './icons-provider';
import { AVAILABLE_LANGS, INTL_TAGS, readInitialLang } from './services/language-service';
import { TranslocoHttpLoader } from './services/transloco-loader';

// Všech pět najednou — data jsou drobná (jednotky kB) a runtime přepínání (LanguageService)
// by je jinak muselo dotahovat asynchronně uprostřed překreslení. Nad ~6 jazyků zvážit lazy
// import() (docs/lokalizace.md, plán expanze), dokud se to appce vyplatí eagerně.
registerLocaleData(cs);
registerLocaleData(sk);
registerLocaleData(en);
registerLocaleData(pl);
registerLocaleData(de);

export const appConfig: ApplicationConfig = {
  providers: [
    // Bez zone.js (Angular 22 generuje zoneless projekty ve výchozím stavu) — change
    // detection běží ze signálů, provideZoneChangeDetection() by vyžadovalo zone.js polyfill
    // navíc a s ničím tady se nepoužívá.
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // LOCALE_ID i NZ_I18N se v Angularu vyhodnotí JEDNOU při bootstrapu — bereme uloženou
    // volbu z předchozí návštěvy, aby první vykreslení sedělo. Runtime přepínání jde přes
    // LanguageService (NzI18nService.setLocale), NE přes tenhle provider — a uživatelsky
    // viditelné formátování čísel/měny/data jde přes FormatService (Intl.*), ne CurrencyPipe/
    // DatePipe, protože měna přichází z dat (multi-currency), ne z locale (docs/lokalizace.md).
    { provide: LOCALE_ID, useValue: INTL_TAGS[readInitialLang()] },
    provideNzI18n(cs_CZ),
    provideNzIcons(icons),
    // Vyžaduje ng-zorro-antd v22+ pro nz-date-picker (price-entry-page, observed_at) — appka
    // nepoužívá date-fns, nativní adaptér nad vestavěným Date stačí. BEZ locale by
    // NativeDateAdapter spadl natvrdo na 'en-US' (viz jeho konstruktor) — kalendář by měl
    // anglické dny/měsíce a týden od neděle bez ohledu na zvolený jazyk appky. Runtime
    // přepnutí jazyka řeší LanguageService (NzDateAdapter.setLocale), tohle jen nastaví
    // hodnotu pro první vykreslení, stejný vzor jako provideNzI18n(cs_CZ) o řádek výš.
    // firstDayOfWeek natvrdo na pondělí — všech pět jazyků appky (cs/sk/pl/de/en-GB) ho má
    // stejné, a bez explicitní hodnoty by se muselo odvozovat z Intl.Locale().weekInfo, což
    // není podporované všude.
    provideNzNativeDateAdapter({ locale: INTL_TAGS[readInitialLang()], firstDayOfWeek: 1 }),
    provideTransloco({
      config: {
        availableLangs: AVAILABLE_LANGS as unknown as string[],
        defaultLang: 'cs',
        fallbackLang: 'cs',
        reRenderOnLangChange: true, // bez tohohle runtime přepínání jazyka nefunguje
        missingHandler: { useFallbackTranslation: true, allowEmpty: false },
        prodMode: !isDevMode(),
        // Bez tohoto se alias scope camelCasuje (`price-entry` → `priceEntry`), takže by se
        // bundl slil do jazyka pod jiným jménem, než jakým si o klíč říkají šablony/TS
        // (`t('price-entry.title')`, `public/i18n/price-entry/`) — docs/lokalizace.md.
        scopes: { keepCasing: true },
      },
      loader: TranslocoHttpLoader,
    }),
    provideTranslocoMessageformat(),
    provideHttpClient(
      withInterceptors([tokenInterceptor, languageInterceptor, displayCurrencyInterceptor]),
    ),
    // Jazyk PŘED přihlášením — ať appka nebliká nepřeloženými klíči, než se ověří token.
    provideAppInitializer(languageInitializer()),
    provideAppInitializer(authInitializer()),
    // PŘED initial navigací routeru — jinak by NavigationHistoryService o té úvodní navigaci
    // nevěděl (viz func/navigation-history-initializer.ts).
    provideAppInitializer(navigationHistoryInitializer),
  ],
};
