import { inject } from '@angular/core';
import { Observable, catchError, of } from 'rxjs';
import { AuthService } from '../services/auth-service';

/**
 * Při startu appky zkusí obnovit přihlášení z httpOnly refresh cookie. Pokud cookie chybí
 * nebo je neplatná, appka jen zůstane odhlášená (T0 anonym) — to není chyba, viz
 * docs/reputace.md, "anonym vidí hodnotu, ne objem".
 */
export function authInitializer(): () => Observable<unknown> {
  return () => {
    const authService = inject(AuthService);
    return authService.refresh().pipe(catchError(() => of(null)));
  };
}
