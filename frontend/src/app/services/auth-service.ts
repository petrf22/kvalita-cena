import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, tap } from 'rxjs';
import { EmailChangeRequestResponse, OtpRequestResponse, TokenResponse } from '../models/auth';

/**
 * Passwordless přihlášení (e-mail → OTP kód → token) — viz docs/soukromi.md v backendu.
 *
 * Refresh token webového klienta jde VÝHRADNĚ jako httpOnly cookie (nastavuje ji backend),
 * appka ho nikdy nevidí ani neukládá. Access token žije jen v paměti (signál), nikdy
 * v localStorage — po refreshi stránky se obnoví přes {@link refresh} díky cookie.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly accessTokenSignal = signal<string | null>(null);

  readonly accessToken = this.accessTokenSignal.asReadonly();
  readonly isLoggedIn = computed(() => this.accessTokenSignal() !== null);

  requestOtp(email: string): Observable<OtpRequestResponse> {
    return this.http.post<OtpRequestResponse>('/api/auth/otp/request', { email });
  }

  /**
   * `termsAccepted` se vyžaduje jen při JIT registraci nového účtu (docs/soukromi.md, "GDPR") —
   * backend ho ignoruje, pokud e-mail už patří existujícímu účtu (přihlášení, ne registrace).
   */
  verifyOtp(
    challengeUid: string,
    code: string,
    email: string,
    termsAccepted: boolean,
  ): Observable<TokenResponse> {
    return this.http
      .post<TokenResponse>(
        '/api/auth/otp/verify',
        { challengeUid, code, email, termsAccepted },
        { withCredentials: true },
      )
      .pipe(tap((token) => this.accessTokenSignal.set(token.accessToken)));
  }

  /** Zkusí obnovit přihlášení z httpOnly cookie (volá se při startu appky). */
  refresh(): Observable<TokenResponse> {
    return this.http
      .post<TokenResponse>('/api/auth/refresh', {}, { withCredentials: true })
      .pipe(tap((token) => this.accessTokenSignal.set(token.accessToken)));
  }

  /**
   * Přístupový token vypršel/je neplatný — server to od "nikdy nepřihlášen" nerozezná
   * (mobilní protějšek: `AuthRepository.recoverFromUnauthorized` v `auth/AuthRepository.kt`).
   * Nejdřív zkusí tichý refresh (httpOnly cookie s refresh tokenem bývá pořád platná); až
   * když selže i ten, `accessToken` signál se vyčistí, ať `isLoggedIn` (na něm založené)
   * přestane napříč appkou lhát — jinak zůstávala appka v nekonzistentním stavu: jedna
   * obrazovka dál hlásila "Přihlášen", zatímco jiná ukazovala "vyžaduje přihlášení".
   */
  recoverFromUnauthorized(): Observable<boolean> {
    return this.refresh().pipe(
      map(() => true),
      catchError(() => {
        this.accessTokenSignal.set(null);
        return of(false);
      }),
    );
  }

  logout(): Observable<void> {
    return this.http
      .post<void>('/api/auth/logout', {}, { withCredentials: true })
      .pipe(tap(() => this.accessTokenSignal.set(null)));
  }

  /**
   * Změna přihlašovacího e-mailu — VLASTNÍ tok vedle přihlašovacího OTP (docs/soukromi.md,
   * "Profil uživatele a viditelnost"): kód jde vždy na NOVOU adresu, jinak by šlo o zapole ve
   * formuláři profilu, kterým by se dal účet překlepem zamknout. Odpověď je stejná bez ohledu
   * na to, jestli je adresa volná, nebo už patří jinému účtu (enumerace účtů zůstává nemožná).
   */
  requestEmailChange(newEmail: string): Observable<EmailChangeRequestResponse> {
    return this.http.post<EmailChangeRequestResponse>('/api/auth/email/change/request', {
      email: newEmail,
    });
  }

  confirmEmailChange(challengeUid: string, code: string, newEmail: string): Observable<void> {
    return this.http.post<void>('/api/auth/email/change/confirm', {
      challengeUid,
      code,
      email: newEmail,
    });
  }
}
