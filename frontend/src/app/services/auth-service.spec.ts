import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService } from './auth-service';
import { TokenResponse } from '../models/auth';

/**
 * Hlídání expirace access tokenu — kvůli němu appka přestala tvrdit "přihlášen" a přitom být
 * pro server anonym (prošlý token server tiše odbaví jako anonymní request, viz AuthService).
 * Mobilní protějšek téhle logiky: `auth/AccessTokenExpiryTest.kt`.
 */
describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;
  let now: number;

  const tokenResponse = (accessToken: string, expiresInSec = 600): TokenResponse => ({
    accessToken,
    refreshToken: null,
    newUser: false,
    expiresInSec,
  });

  /** Přihlásí službu tak, jako by doběhl start appky (`authInitializer`). */
  const login = (accessToken: string, expiresInSec = 600) => {
    service.refresh().subscribe();
    http.expectOne('/api/auth/refresh').flush(tokenResponse(accessToken, expiresInSec));
  };

  beforeEach(() => {
    now = 1_000_000;
    vi.spyOn(performance, 'now').mockImplementation(() => now);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
  });

  it('anonyma nezdržuje ani jedním requestem navíc', async () => {
    await expect(firstValue(service.validAccessToken())).resolves.toBeNull();
  });

  it('platný token vrací beze změny, bez volání serveru', async () => {
    login('token-1');
    now += 60_000;
    await expect(firstValue(service.validAccessToken())).resolves.toBe('token-1');
  });

  it('token po vypršení obnoví dřív, než s ním vyrazí na server', async () => {
    login('token-1', 600);
    now += 600_001;

    const result = firstValue(service.validAccessToken());
    http.expectOne('/api/auth/refresh').flush(tokenResponse('token-2'));
    await expect(result).resolves.toBe('token-2');
  });

  /** Jádro opravy: token, kterému zbývá míň než rezerva, by mohl vypršet cestou na server. */
  it('obnoví token i těsně před vypršením', async () => {
    login('token-1', 600);
    now += 600_000 - 30_000;

    const result = firstValue(service.validAccessToken());
    http.expectOne('/api/auth/refresh').flush(tokenResponse('token-2'));
    await expect(result).resolves.toBe('token-2');
  });

  it('souběžné požadavky obnoví token jen jednou (refresh token na serveru rotuje)', async () => {
    login('token-1', 600);
    now += 600_001;

    const first = firstValue(service.validAccessToken());
    const second = firstValue(service.validAccessToken());
    http.expectOne('/api/auth/refresh').flush(tokenResponse('token-2'));

    await expect(first).resolves.toBe('token-2');
    await expect(second).resolves.toBe('token-2');
  });

  it('odmítnutá obnova (401) session zruší, ať isLoggedIn nelže', async () => {
    login('token-1', 600);
    now += 600_001;

    const result = firstValue(service.validAccessToken());
    http.expectOne('/api/auth/refresh').flush(null, { status: 401, statusText: 'Unauthorized' });

    await expect(result).resolves.toBeNull();
    expect(service.isLoggedIn()).toBe(false);
  });

  it('výpadek sítě uživatele NEodhlásí', async () => {
    login('token-1', 600);
    now += 600_001;

    const result = firstValue(service.validAccessToken());
    http.expectOne('/api/auth/refresh').error(new ProgressEvent('error'));

    await expect(result).resolves.toBeNull();
    expect(service.isLoggedIn()).toBe(true);
  });

  it('nerotuje znovu, když token mezitím obnovil jiný požadavek', async () => {
    login('token-1', 600);
    now += 600_001;

    // Jeden požadavek obnovu dokončí…
    const refreshed = firstValue(service.validAccessToken());
    http.expectOne('/api/auth/refresh').flush(tokenResponse('token-2'));
    await expect(refreshed).resolves.toBe('token-2');

    // …a druhý se teprve teď vrací se svojí 401 na starý token.
    await expect(firstValue(service.recoverFromUnauthorized('token-1'))).resolves.toBe(true);
  });
});

function firstValue<T>(source: { subscribe: (o: (value: T) => void) => unknown }): Promise<T> {
  return new Promise<T>((resolve) => source.subscribe(resolve));
}
