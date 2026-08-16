import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { AuthService } from './auth-service';
import { CountryService } from './country-service';
import { GraphQlService } from './graphql-service';
import { LanguageService } from './language-service';
import { ViewerService } from './viewer-service';

const STORAGE_KEY = 'kac.country';

/**
 * CountryService má čtyři reálné závislosti (HTTP dotaz na countries(), AuthService,
 * LanguageService, ViewerService) — nahrazujeme je lehkými fakes, aby test izoloval jen
 * logiku CountryService samotné (localStorage, no-op na nezměněnou hodnotu, kdy se volá
 * setLocale). HttpClientTestingModule tady nejde použít — Angular 22 unit-test builder
 * (@angular/build:unit-test, viz CLAUDE.md) běží bez JIT compileru, který BrowserXhr
 * z HttpClientTesting vyžaduje.
 */
describe('CountryService', () => {
  let setLocaleCalls: Array<[string, string | undefined]>;
  let loggedIn: boolean;

  beforeEach(() => {
    localStorage.clear();
    setLocaleCalls = [];
    loggedIn = false;
    TestBed.configureTestingModule({
      providers: [
        { provide: GraphQlService, useValue: { execute: () => of({ countries: [] }) } },
        {
          provide: ViewerService,
          useValue: {
            setLocale: (locale: string, country?: string) => {
              setLocaleCalls.push([locale, country]);
              return of({ locale, country: country ?? null });
            },
          },
        },
        { provide: AuthService, useValue: { isLoggedIn: () => loggedIn } },
        { provide: LanguageService, useValue: { lang: () => 'cs' } },
      ],
    });
  });

  it('defaults to a country derived from the current language when nothing was chosen before', () => {
    localStorage.setItem('kac.lang', 'cs');
    const service = TestBed.inject(CountryService);
    expect(service.country()).toBe('CZ');
  });

  it('restores a previously chosen country on construction', () => {
    localStorage.setItem(STORAGE_KEY, 'SK');
    const service = TestBed.inject(CountryService);
    expect(service.country()).toBe('SK');
  });

  it('persists the choice to localStorage', () => {
    const service = TestBed.inject(CountryService);
    service.setCountry('PL');
    TestBed.tick();
    expect(localStorage.getItem(STORAGE_KEY)).toBe('PL');
  });

  it('ignores setting the same country again (no-op, does not call setLocale)', () => {
    localStorage.setItem(STORAGE_KEY, 'SK');
    const service = TestBed.inject(CountryService);
    service.setCountry('SK');
    expect(setLocaleCalls).toEqual([]);
  });

  it('anonymous user only changes the local preference, never calls setLocale', () => {
    loggedIn = false;
    const service = TestBed.inject(CountryService);
    service.setCountry('PL');
    expect(service.country()).toBe('PL');
    expect(setLocaleCalls).toEqual([]);
  });

  it('logged-in user also pushes the choice to the server via setLocale', () => {
    loggedIn = true;
    const service = TestBed.inject(CountryService);
    service.setCountry('PL');
    expect(setLocaleCalls).toEqual([['cs', 'PL']]);
  });
});
