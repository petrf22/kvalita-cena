import { HttpErrorResponse, HttpEvent, HttpHandlerFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from '../services/auth-service';

function withAuthHeader(
  req: HttpRequest<unknown>,
  accessToken: string | null,
): HttpRequest<unknown> {
  return accessToken ? req.clone({ setHeaders: { Authorization: `Bearer ${accessToken}` } }) : req;
}

/**
 * REST protějšek `GraphQlService`: 401 z chráněného REST endpointu (`/api/media/**` — upload
 * fotky/avataru) může znamenat i vypršelý access token (server to od anonyma nerozezná, viz
 * `AuthService.recoverFromUnauthorized`), ne nutně skutečné "nepřihlášen". Vlastní `/api/auth/`
 * cesty jsou vyloučené, ať se recovery (interně volá `refresh()`, taky přes tenhle interceptor)
 * nezacyklí sama do sebe.
 */
export function tokenInterceptor(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
): Observable<HttpEvent<unknown>> {
  const authService = inject(AuthService);
  const accessToken = authService.accessToken();
  const authorizedReq = withAuthHeader(req, accessToken);

  return next(authorizedReq).pipe(
    catchError((error: unknown) => {
      if (
        !accessToken ||
        !(error instanceof HttpErrorResponse) ||
        error.status !== 401 ||
        req.url.startsWith('/api/auth/')
      ) {
        return throwError(() => error);
      }
      return authService.recoverFromUnauthorized().pipe(
        switchMap((recovered) => {
          if (!recovered) return throwError(() => error);
          return next(withAuthHeader(req, authService.accessToken()));
        }),
      );
    }),
  );
}
