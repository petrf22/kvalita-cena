import { Injectable, inject } from '@angular/core';
import { defer, map, of, switchMap } from 'rxjs';
import { AuthService } from './auth-service';
import { GraphQlService } from './graphql-service';
import { graphql } from '../models/generated';
import type { UpdateProfileInput } from '../models/generated/graphql';

@Injectable({ providedIn: 'root' })
export class ViewerService {
  private readonly graphQl = inject(GraphQlService);
  private readonly auth = inject(AuthService);

  /**
   * Veřejná identita přihlášeného uživatele — null pro anonyma.
   *
   * `me` je jediný dotaz, kde odmítnutý token NENÍ k rozeznání od anonyma: server vrátí prostě
   * `null` bez `errors` (`ViewerGraphQlController.me`), takže obecná recovery v `GraphQlService`
   * se na něm nikdy nechytí. Když jsme se ptali S tokenem a přišlo `null`, token tedy server
   * neuznal — jednou zkusíme obnovu a dotaz zopakujeme; když je `null` i podruhé, session je
   * opravdu pryč a `recoverFromUnauthorized` ji už zrušila. Mobilní protějšek:
   * `GraphQlClient.me()`.
   */
  me() {
    const document = graphql(`
      query Me {
        me {
          publicHandle
          displayName
          createdAt
          trusted
          moderator
          locale
          country
          profile {
            ...ProfileFields
          }
        }
      }
    `);
    const query = () => this.graphQl.execute(document).pipe(map((data) => data.me));
    return defer(() => {
      const tokenBefore = this.auth.accessToken();
      return query().pipe(
        switchMap((viewer) => {
          if (viewer || tokenBefore === null) return of(viewer);
          return this.auth
            .recoverFromUnauthorized(tokenBefore)
            .pipe(switchMap((recovered) => (recovered ? query() : of(null))));
        }),
      );
    });
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

  /**
   * Jméno, příjmení, přezdívka, telefon, kontaktní e-mail a viditelnost (docs/soukromi.md,
   * "Profil uživatele a viditelnost"). Avatar se nahrává přes REST (MediaService.uploadAvatar),
   * tahle mutace ho jen umí smazat (viz deleteAvatar).
   */
  updateProfile(input: UpdateProfileInput) {
    const document = graphql(`
      mutation UpdateProfile($input: UpdateProfileInput!) {
        updateProfile(input: $input) {
          publicHandle
          displayName
          profile {
            ...ProfileFields
          }
        }
      }
    `);
    return this.graphQl.execute(document, { input }).pipe(map((data) => data.updateProfile));
  }

  /** Smazání avatara profilu. */
  deleteAvatar() {
    const document = graphql(`
      mutation DeleteAvatar {
        deleteAvatar {
          publicHandle
          displayName
          profile {
            ...ProfileFields
          }
        }
      }
    `);
    return this.graphQl.execute(document).pipe(map((data) => data.deleteAvatar));
  }
}
