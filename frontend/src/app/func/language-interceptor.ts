import { HttpEvent, HttpHandlerFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable } from 'rxjs';
import { LanguageService } from '../services/language-service';

/** Accept-Language na každý request — backend podle ní lokalizuje chyby i OTP e-mail (docs/lokalizace.md). */
export function languageInterceptor(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
): Observable<HttpEvent<unknown>> {
  const language = inject(LanguageService);
  return next(req.clone({ setHeaders: { 'Accept-Language': language.lang() } }));
}
