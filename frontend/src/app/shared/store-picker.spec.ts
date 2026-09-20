import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { Subject } from 'rxjs';
import { vi } from 'vitest';
import { StorePicker } from './store-picker';
import { Store } from '../models/catalog';
import { StoreService } from '../services/store-service';
import { LastStoreService } from '../services/last-store-service';
import { AuthService } from '../services/auth-service';
import { CountryService } from '../services/country-service';

describe('StorePicker – zapamatování a vymazání', () => {
  let response: Subject<Store | null>;
  let picker: StorePicker;
  let last: {
    read: ReturnType<typeof vi.fn>;
    remember: ReturnType<typeof vi.fn>;
    clear: ReturnType<typeof vi.fn>;
  };
  const store = { id: '1', name: 'Obchod', city: 'Brno', country: 'CZ' } as Store;
  beforeEach(() => {
    response = new Subject();
    last = { read: vi.fn(() => '1'), remember: vi.fn(), clear: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: StoreService, useValue: { getById: () => response } },
        { provide: LastStoreService, useValue: last },
        { provide: AuthService, useValue: {} },
        { provide: CountryService, useValue: { country: signal('CZ') } },
        { provide: TranslocoService, useValue: {} },
      ],
    });
    picker = TestBed.runInInjectionContext(() => new StorePicker());
  });
  it('obnoví poslední obchod a jedním vymazáním zruší výběr i paměť', () => {
    const selected = vi.fn();
    picker.selectedStoreChange.subscribe(selected);
    picker.ngOnInit();
    response.next(store);
    expect(selected).toHaveBeenLastCalledWith(store);
    picker.onSelectId(null);
    expect(selected).toHaveBeenLastCalledWith(null);
    expect(last.clear).toHaveBeenCalledOnce();
  });
  it('pozdní načtení nevrátí vymazaný obchod', () => {
    picker.ngOnInit();
    picker.onSelectId(null);
    response.next(store);
    expect(picker['selectedStore']()).toBeNull();
    expect(last.remember).not.toHaveBeenCalled();
  });
  it('výpadek sítě nemaže zapamatovanou volbu', () => {
    picker.ngOnInit();
    response.error(new Error('offline'));
    expect(last.clear).not.toHaveBeenCalled();
  });

  it('neplatné ID od rodiče nesmaže jiný zapamatovaný obchod', () => {
    const selected = vi.fn();
    picker.selectedStoreChange.subscribe(selected);
    // Vstup od rodiče; input() se mimo fixture nastavit nedá, stačí ale signálová funkce.
    (picker as unknown as { selectedStoreId: () => string | null }).selectedStoreId = () => '2';
    picker.ngOnInit();
    response.next(null);
    expect(last.clear).not.toHaveBeenCalled();
    expect(selected).toHaveBeenLastCalledWith(null);
  });

  it('"Změnit obchod" jen otevře seznam, výběr ani paměť nemaže', () => {
    picker.ngOnInit();
    response.next(store);
    picker['picking'].set(true);
    expect(picker['selectedStore']()).toEqual(store);
    expect(last.clear).not.toHaveBeenCalled();
    picker.onSelectId(store.id);
    expect(picker['picking']()).toBe(false);
  });
});
