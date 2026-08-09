import { inject } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { Observable, catchError, of } from 'rxjs';
import { LanguageService } from '../services/language-service';

/**
 * Načte kořenový bundle zvoleného jazyka PŘED prvním vykreslením appky, ať se stránka
 * neproblikne nepřeloženými klíči. Musí běžet PŘED `authInitializer` (viz app.config.ts)
 * — `inject(LanguageService)` tady spustí i její konstruktor (efekt na `nzI18n`/`documentElement`).
 * Výpadek/timeout appku nesmí zablokovat, proto `catchError` — bez bundlu Transloco spadne na
 * `missingHandler.useFallbackTranslation` (čeština).
 */
export function languageInitializer(): () => Observable<unknown> {
  return () => {
    const transloco = inject(TranslocoService);
    const language = inject(LanguageService);
    return transloco.load(language.lang()).pipe(catchError(() => of(null)));
  };
}
