import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth-service';

/**
 * Stránky, které bez přihlášení nemají co ukázat (účtenky vidí jen vlastník, docs/soukromi.md)
 * — nepřihlášeného pošle rovnou na přihlášení s návratem zpět (`returnUrl`, login-page).
 *
 * `isLoggedIn` je po startu spolehlivý, protože `authInitializer` obnoví session z refresh
 * cookie dřív, než router poprvé naviguje. Že token za běhu vyprší, tu řešit nejde — to hlídá
 * `AuthService.validAccessToken` u každého requestu a stránka až podle odpovědi serveru.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  if (inject(AuthService).isLoggedIn()) return true;
  return inject(Router).createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};

/**
 * Cílová adresa po přihlášení — jen cesta uvnitř appky. Cokoli jiného (`//evil.example`,
 * `https://…`) se zahodí, jinak by šlo podstrčit odkaz na přihlášení, který po něm přesměruje
 * ven (open redirect).
 */
export function safeReturnUrl(value: string | null | undefined): string | null {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) {
    return null;
  }
  return value;
}
