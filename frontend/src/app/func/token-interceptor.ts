import { HttpErrorResponse, HttpEvent, HttpHandlerFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from '../services/auth-service';

/**
 * Cesty, které se NIKDY nesmí ptát na čerstvý token — samotná obnova (a přihlašovací tok, kde
 * ještě žádný token není) by přes `validAccessToken` volala samu sebe. Ostatní `/api/auth/`
 * cesty (změna e-mailu) token potřebují, a to platný.
 */
const NO_REFRESH_PATHS = ['/api/auth/refresh', '/api/auth/otp/', '/api/auth/logout'];

function withAuthHeader(
  req: HttpRequest<unknown>,
  accessToken: string | null,
): HttpRequest<unknown> {
  return accessToken ? req.clone({ setHeaders: { Authorization: `Bearer ${accessToken}` } }) : req;
}

/**
 * Přikládá access token a stará se o to, aby byl PLATNÝ: `validAccessToken` ho podle expirace
 * podle potřeby nejdřív obnoví. Čekat s obnovou až na chybu nestačí — prošlý token server tiše
 * odbaví jako anonymní request (HTTP 200), takže by se na něj nikdy nepřišlo (viz `AuthService`).
 *
 * REST protějšek `GraphQlService`: 401 z chráněného REST endpointu (`/api/media/**` — upload
 * fotky/avataru) může i tak znamenat odmítnutý token (inkrement `token_version`, pozastavený
 * účet), ne nutně skutečné "nepřihlášen". Vlastní `/api/auth/` cesty jsou z recovery vyloučené,
 * ať se sama nezacyklí.
 */
export function tokenInterceptor(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
): Observable<HttpEvent<unknown>> {
  const authService = inject(AuthService);
  if (NO_REFRESH_PATHS.some((path) => req.url.startsWith(path))) {
    return next(withAuthHeader(req, authService.accessToken()));
  }

  return authService.validAccessToken().pipe(
    switchMap((accessToken) =>
      next(withAuthHeader(req, accessToken)).pipe(
        catchError((error: unknown) => {
          if (
            !accessToken ||
            !(error instanceof HttpErrorResponse) ||
            error.status !== 401 ||
            req.url.startsWith('/api/auth/')
          ) {
            return throwError(() => error);
          }
          return authService.recoverFromUnauthorized(accessToken).pipe(
            switchMap((recovered) => {
              if (!recovered) return throwError(() => error);
              return next(withAuthHeader(req, authService.accessToken()));
            }),
          );
        }),
      ),
    ),
  );
}
