import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { OtpRequestResponse, TokenResponse } from '../models/auth';

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

  verifyOtp(challengeUid: string, code: string, email: string): Observable<TokenResponse> {
    return this.http
      .post<TokenResponse>(
        '/api/auth/otp/verify',
        { challengeUid, code, email },
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

  logout(): Observable<void> {
    return this.http
      .post<void>('/api/auth/logout', {}, { withCredentials: true })
      .pipe(tap(() => this.accessTokenSignal.set(null)));
  }
}
