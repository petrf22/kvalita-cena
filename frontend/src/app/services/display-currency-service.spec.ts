import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { DisplayCurrencyService } from './display-currency-service';

const STORAGE_KEY = 'kac.currency';

describe('DisplayCurrencyService', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
  });

  it('defaults to null (store currency) when nothing was chosen before', () => {
    const service = TestBed.inject(DisplayCurrencyService);
    expect(service.currency()).toBeNull();
  });

  it('ignores garbage previously left in localStorage', () => {
    localStorage.setItem(STORAGE_KEY, 'HUF'); // appka HUF nepodporuje
    const service = TestBed.inject(DisplayCurrencyService);
    expect(service.currency()).toBeNull();
  });

  it('restores a previously chosen currency on construction', () => {
    localStorage.setItem(STORAGE_KEY, 'USD');
    const service = TestBed.inject(DisplayCurrencyService);
    expect(service.currency()).toBe('USD');
  });

  it('persists the choice to localStorage', async () => {
    const service = TestBed.inject(DisplayCurrencyService);
    service.setCurrency('EUR');
    TestBed.tick();
    expect(localStorage.getItem(STORAGE_KEY)).toBe('EUR');
  });

  it('clears localStorage when switching back to the store currency', async () => {
    localStorage.setItem(STORAGE_KEY, 'PLN');
    const service = TestBed.inject(DisplayCurrencyService);
    service.setCurrency(null);
    TestBed.tick();
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });
});
