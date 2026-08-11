import { HttpEvent, HttpHandlerFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable } from 'rxjs';
import { DisplayCurrencyService } from '../services/display-currency-service';

/**
 * X-Display-Currency jen když si uživatel zvolil jinou měnu než "měnu obchodu" (docs/lokalizace.md)
 * — bez hlavičky server nic nepřepočítává a odpověď je bit-shodná se stavem před touhle funkcí.
 */
export function displayCurrencyInterceptor(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
): Observable<HttpEvent<unknown>> {
  const currency = inject(DisplayCurrencyService).currency();
  if (!currency) return next(req);
  return next(req.clone({ setHeaders: { 'X-Display-Currency': currency } }));
}
