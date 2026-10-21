import { Router } from '@angular/router';
import { ErrorCode } from '../../models/generated/enums';
import { GraphQlAppError } from '../../services/graphql-service';

/**
 * Session vypršela za běhu stránky a obnova tokenu nevyšla — server odpoví
 * `RECEIPT_REQUIRES_LOGIN` (anonymní request, kořenový CLAUDE.md, „Prošlý access token").
 * Takovou odpověď nejde ukázat jako prázdný seznam ani „účtenka neexistuje" — uživatel má
 * účtenky, jen se musí znovu přihlásit.
 */
export function requiresLogin(err: unknown): boolean {
  return err instanceof GraphQlAppError && err.code === ErrorCode.ReceiptRequiresLogin;
}

/** Na přihlášení s návratem na aktuální stránku (login-page čte `returnUrl`). */
export function loginRedirect(router: Router): void {
  void router.navigate(['/login'], { queryParams: { returnUrl: router.url } });
}
