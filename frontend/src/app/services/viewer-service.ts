import { Injectable, inject } from '@angular/core';
import { map } from 'rxjs';
import { GraphQlService } from './graphql-service';
import { graphql } from '../models/generated';

@Injectable({ providedIn: 'root' })
export class ViewerService {
  private readonly graphQl = inject(GraphQlService);

  /** Veřejná identita přihlášeného uživatele — null pro anonyma. */
  me() {
    const document = graphql(`
      query Me {
        me {
          publicHandle
          displayName
          createdAt
          trusted
          locale
          country
        }
      }
    `);
    return this.graphQl.execute(document).pipe(map((data) => data.me));
  }

  /**
   * Uloží preferovaný jazyk (a volitelně zemi) na server — VÝHRADNĚ pro asynchronní výstup
   * (OTP e-mail, později notifikace), viz LanguageService a docs/lokalizace.md. Synchronní
   * odpovědi API se řídí hlavičkou Accept-Language, ne touhle hodnotou.
   */
  setLocale(locale: string, country?: string) {
    const document = graphql(`
      mutation SetLocale($locale: String!, $country: String) {
        setLocale(locale: $locale, country: $country) {
          locale
          country
        }
      }
    `);
    return this.graphQl.execute(document, { locale, country }).pipe(map((data) => data.setLocale));
  }
}
