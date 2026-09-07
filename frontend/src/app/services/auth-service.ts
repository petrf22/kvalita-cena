import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, finalize, map, of, shareReplay, tap } from 'rxjs';
import {
  AccountDeleteRequestResponse,
  EmailChangeRequestResponse,
  OtpRequestResponse,
  TokenResponse,
} from '../models/auth';

/**
 * Passwordless přihlášení (e-mail → OTP kód → token) — viz docs/soukromi.md v backendu.
 *
 * Refresh token webového klienta jde VÝHRADNĚ jako httpOnly cookie (nastavuje ji backend),
 * appka ho nikdy nevidí ani neukládá. Access token žije jen v paměti (signál), nikdy
 * v localStorage — po refreshi stránky se obnoví přes {@link refresh} díky cookie.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  /**
   * Rezerva před vypršením: token, kterému zbývá míň, se považuje za neplatný a obnoví se dřív,
   * než se s ním vyrazí na server — jinak by stihl vypršet cestou tam a server by request tiše
   * odbavil jako ANONYMNÍ. Mobilní protějšek: `auth/AccessTokenExpiry.kt`.
   */
  private static readonly REFRESH_MARGIN_MS = 30_000;

  private readonly http = inject(HttpClient);
  private readonly accessTokenSignal = signal<string | null>(null);
  /** `performance.now()`, ne `Date.now()` — životnost tokenu je doba, ne okamžik, takže
   *  přenastavení systémových hodin nesmí platný token prohlásit za prošlý ani naopak. */
  private readonly expiresAtSignal = signal<number | null>(null);
  private refreshInFlight: Observable<string | null> | null = null;

  readonly accessToken = this.accessTokenSignal.asReadonly();
  readonly isLoggedIn = computed(() => this.accessTokenSignal() !== null);

  /**
   * Jediný způsob, jak se dostat k access tokenu pro request — vrátí ho, dokud je JEŠTĚ platný,
   * jinak ho tiše obnoví z refresh cookie.
   *
   * Čekat s obnovou na chybu ze serveru nejde: prošlý access token `JwtAuthenticationFilter`
   * mlčky zahodí a request doběhne jako ANONYMNÍ (HTTP 200, žádné `errors`), takže dotaz
   * s anonymní variantou — `me`, hledání s vlastními DRAFTy, okno grafu — vrátí normální, jen
   * ochuzenou odpověď. Appka pak tvrdila "přihlášen" a přitom serveru byla cizí (záložka
   * nechaná otevřená přes 10 minut). Mobilní protějšek: `AuthRepository.validAccessToken`.
   */
  validAccessToken(): Observable<string | null> {
    const usable = this.usableAccessToken();
    if (usable !== null) return of(usable);
    // Anonym nemá co obnovovat — refresh cookie je httpOnly, takže "nemáme token" je jediné,
    // co o tom appka ví, a je to totéž kritérium jako `isLoggedIn`.
    if (this.accessTokenSignal() === null) return of(null);
    return this.sharedRefresh();
  }

  private usableAccessToken(): string | null {
    const token = this.accessTokenSignal();
    const expiresAt = this.expiresAtSignal();
    if (token === null || expiresAt === null) return null;
    return performance.now() < expiresAt - AuthService.REFRESH_MARGIN_MS ? token : null;
  }

  /**
   * Obnova běží vždy jen jednou (single-flight) a ostatní na ni počkají — refresh token na
   * serveru ROTUJE a jeho znovupoužití mimo 30s grace okno revokuje celou rodinu tokenů
   * (`RefreshTokenService.rotate`), takže dva souběžné požadavky by uživatele odhlásily
   * "kvůli podezření na krádež".
   */
  private sharedRefresh(): Observable<string | null> {
    if (this.refreshInFlight) return this.refreshInFlight;
    const inFlight: Observable<string | null> = this.refresh().pipe(
      map(() => this.accessTokenSignal()),
      catchError((error: unknown) => {
        // 401 znamená neplatnou/prošlou/revokovanou refresh cookie (`SESSION_EXPIRED`) —
        // session je fakticky pryč a musí zmizet i z appky, ať `isLoggedIn` nelže. Cokoli
        // jiného (výpadek sítě, chyba serveru) session NECHÁVÁ být: odhlásit člověka kvůli
        // vypadlému wi-fi by ho připravilo o přihlášení pro nic.
        if (error instanceof HttpErrorResponse && error.status === 401) this.clearSession();
        return of(null);
      }),
      finalize(() => {
        if (this.refreshInFlight === inFlight) this.refreshInFlight = null;
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    this.refreshInFlight = inFlight;
    return inFlight;
  }

  private applyToken(token: TokenResponse): void {
    this.accessTokenSignal.set(token.accessToken);
    this.expiresAtSignal.set(performance.now() + token.expiresInSec * 1000);
  }

  /** Konec session — ať `isLoggedIn` (a všechno na něm založené) napříč appkou nelže. */
  private clearSession(): void {
    this.accessTokenSignal.set(null);
    this.expiresAtSignal.set(null);
  }

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
      .pipe(tap((token) => this.applyToken(token)));
  }

  /** Zkusí obnovit přihlášení z httpOnly cookie (volá se při startu appky). */
  refresh(): Observable<TokenResponse> {
    return this.http
      .post<TokenResponse>('/api/auth/refresh', {}, { withCredentials: true })
      .pipe(tap((token) => this.applyToken(token)));
  }

  /**
   * Server odmítl token, který appka považovala za platný — vypršet neměl (`validAccessToken`
   * ho hlídá), takže jde o důvod na straně serveru: inkrement `token_version` (globální
   * odhlášení), pozastavený účet, restart backendu s jiným `JWT_SECRET`. Mobilní protějšek:
   * `AuthRepository.recoverFromUnauthorized` v `auth/AuthRepository.kt`.
   *
   * `usedToken` je token, se kterým volající narazil — když mezitím obnovu udělal jiný
   * souběžný požadavek, další rotace se nekoná.
   */
  recoverFromUnauthorized(usedToken: string | null): Observable<boolean> {
    const current = this.usableAccessToken();
    if (current !== null && current !== usedToken) return of(true);
    return this.sharedRefresh().pipe(map((token) => token !== null));
  }

  logout(): Observable<void> {
    return this.http
      .post<void>('/api/auth/logout', {}, { withCredentials: true })
      .pipe(tap(() => this.clearSession()));
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

  /**
   * Výmaz účtu (docs/zasady-ochrany-osobnich-udaju.md, "Tvá práva") — dvoukrokový OTP tok jako
   * změna e-mailu, jen na už vlastněnou adresu ({@code AccountController.java}).
   */
  requestAccountDelete(): Observable<AccountDeleteRequestResponse> {
    return this.http.post<AccountDeleteRequestResponse>('/api/me/delete/request', {});
  }

  confirmAccountDelete(challengeUid: string, code: string): Observable<void> {
    return this.http
      .post<void>('/api/me/delete/confirm', { challengeUid, code })
      .pipe(tap(() => this.clearSession()));
  }
}
