import { registerLocaleData } from '@angular/common';
import cs from '@angular/common/locales/cs';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  LOCALE_ID,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { cs_CZ, provideNzI18n } from 'ng-zorro-antd/i18n';
import { provideNzIcons } from 'ng-zorro-antd/icon';

import { routes } from './app.routes';
import { authInitializer } from './func/auth-initializer';
import { tokenInterceptor } from './func/token-interceptor';
import { icons } from './icons-provider';

registerLocaleData(cs);

export const appConfig: ApplicationConfig = {
  providers: [
    // Bez zone.js (Angular 22 generuje zoneless projekty ve výchozím stavu) — change
    // detection běží ze signálů, provideZoneChangeDetection() by vyžadovalo zone.js polyfill
    // navíc a s ničím tady se nepoužívá.
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    { provide: LOCALE_ID, useValue: 'cs-CZ' },
    provideNzI18n(cs_CZ),
    provideNzIcons(icons),
    provideHttpClient(withInterceptors([tokenInterceptor])),
    provideAppInitializer(authInitializer()),
  ],
};
