import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Translation, TranslocoLoader } from '@jsverse/transloco';

/**
 * `langPath` je buď kořenový jazyk ('cs') nebo scope+jazyk ('search/cs') — Transloco si
 * cestu sestaví samo podle `provideTranslocoScope`. Soubory leží v `public/i18n/`, které
 * Angular servíruje jako statické assety (angular.json, `assets: [{ glob: '**\/*', input:
 * 'public' }]`), takže žádný speciální build krok navíc není potřeba.
 */
@Injectable({ providedIn: 'root' })
export class TranslocoHttpLoader implements TranslocoLoader {
  private readonly http = inject(HttpClient);

  getTranslation(langPath: string) {
    return this.http.get<Translation>(`/i18n/${langPath}.json`);
  }
}
