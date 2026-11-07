import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { OsmLookupAddress, StorePicker } from './store-picker';
import { OsmStoreCandidate, OsmStoreCandidateResult, Store } from '../models/catalog';
import { OsmLookupStatus } from '../models/generated/enums';
import { StoreService } from '../services/store-service';
import { LastStoreService } from '../services/last-store-service';
import { AuthService } from '../services/auth-service';
import { ConnectivityService } from '../services/connectivity-service';
import { CountryService } from '../services/country-service';
import { GraphQlAppError } from '../services/graphql-service';

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

  it('bez obnovy posledního obchodu (účtenka) nic nepředvybere ani nenačte', () => {
    const selected = vi.fn();
    picker.selectedStoreChange.subscribe(selected);
    (picker as unknown as { restoreLast: () => boolean }).restoreLast = () => false;
    picker.ngOnInit();
    response.next(store);
    expect(last.read).not.toHaveBeenCalled();
    expect(selected).not.toHaveBeenCalled();
    expect(picker['selectedStore']()).toBeNull();
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

describe('StorePicker – nabídka z OpenStreetMap u adresy z účtenky', () => {
  const address: OsmLookupAddress = {
    name: 'ALBERT',
    street: 'Nádražní 1',
    postalCode: '100 00',
    city: 'Vzorov',
  };
  const candidate = (osmRef: string): OsmStoreCandidate => ({
    name: `Albert ${osmRef}`,
    street: 'Nádražní 1',
    city: 'Vzorov',
    postalCode: '100 00',
    country: 'CZ',
    lat: 50.1,
    lon: 14.4,
    osmRef,
    brand: 'Albert',
  });
  const loaded = (candidates: OsmStoreCandidate[]): OsmStoreCandidateResult => ({
    status: OsmLookupStatus.Ok,
    candidates,
    attribution: '© přispěvatelé OpenStreetMap',
  });
  let osmAtAddress: ReturnType<typeof vi.fn>;
  let loggedIn: ReturnType<typeof signal<boolean>>;
  let online: ReturnType<typeof signal<boolean>>;
  let reconnected: Subject<void>;
  let addressInput: ReturnType<typeof signal<OsmLookupAddress | null>>;
  let picker: StorePicker;

  beforeEach(() => {
    osmAtAddress = vi.fn(() => of(loaded([candidate('node/1')])));
    loggedIn = signal(true);
    online = signal(true);
    reconnected = new Subject();
    addressInput = signal<OsmLookupAddress | null>(address);
    TestBed.configureTestingModule({
      providers: [
        { provide: StoreService, useValue: { osmAtAddress } },
        {
          provide: LastStoreService,
          useValue: { read: () => null, remember: vi.fn(), clear: vi.fn() },
        },
        { provide: AuthService, useValue: { isLoggedIn: loggedIn } },
        { provide: CountryService, useValue: { country: signal('CZ') } },
        { provide: ConnectivityService, useValue: { online, reconnected$: reconnected } },
        // Překlad odlišný od klíče — translateError jinak bere klíč za chybějící překlad.
        { provide: TranslocoService, useValue: { translate: (key: string) => `[${key}]` } },
      ],
    });
    picker = TestBed.runInInjectionContext(() => new StorePicker());
    // Vstup od rodiče; input() se mimo fixture nastavit nedá, stačí ale signálová funkce.
    (picker as unknown as { osmAddress: () => OsmLookupAddress | null }).osmAddress = () =>
      addressInput();
  });

  it('jednou na adresu se zeptá bez země a přenačtená účtenka dotaz nezopakuje', () => {
    TestBed.tick();
    expect(osmAtAddress).toHaveBeenCalledExactlyOnceWith(
      'ALBERT',
      'Nádražní 1',
      'Vzorov',
      '100 00',
    );
    expect(picker['osmVisible']()).toEqual([candidate('node/1')]);
    expect(picker['osmAttribution']()).toBe('© přispěvatelé OpenStreetMap');
    addressInput.set({ ...address });
    TestBed.tick();
    expect(osmAtAddress).toHaveBeenCalledOnce();
  });

  it('bez města ani nepřihlášenému se neptá a sekci neukáže', () => {
    addressInput.set({ ...address, city: '  ' });
    TestBed.tick();
    addressInput.set(address);
    loggedIn.set(false);
    TestBed.tick();
    expect(osmAtAddress).not.toHaveBeenCalled();
    expect(picker['osmSectionVisible']()).toBe(false);
  });

  it('výpadek OSM není prázdná nabídka a jde zopakovat', () => {
    osmAtAddress.mockReturnValueOnce(
      of({ status: OsmLookupStatus.Unavailable, candidates: [], attribution: 'OSM' }),
    );
    TestBed.tick();
    expect(picker['osm']().kind).toBe('unavailable');
    picker.loadOsm();
    expect(picker['osm']().kind).toBe('loaded');
    expect(osmAtAddress).toHaveBeenCalledTimes(2);
  });

  it('chybu serveru přeloží podle kódu', () => {
    osmAtAddress.mockReturnValueOnce(
      throwError(() => new GraphQlAppError('STORE_CREATE_REQUIRES_LOGIN', [], 'raw', null)),
    );
    TestBed.tick();
    expect(picker['osmError']()).toBe('[errors.STORE_CREATE_REQUIRES_LOGIN]');
  });

  it('bez sítě čeká a po připojení se dotáhne sama', () => {
    osmAtAddress.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));
    TestBed.tick();
    expect(picker['osm']().kind).toBe('offline');
    reconnected.next();
    expect(picker['osm']().kind).toBe('loaded');
  });

  it('offline prohlížeč se na server vůbec neptá', () => {
    online.set(false);
    TestBed.tick();
    expect(osmAtAddress).not.toHaveBeenCalled();
    expect(picker['osm']().kind).toBe('offline');
  });

  it('ukáže prvních pět kandidátů, zbytek na vyžádání', () => {
    const many = Array.from({ length: 7 }, (_, i) => candidate(`node/${i}`));
    osmAtAddress.mockReturnValueOnce(of(loaded(many)));
    TestBed.tick();
    expect(picker['osmVisible']()).toHaveLength(5);
    expect(picker['osmHiddenCount']()).toBe(2);
    picker.showAllOsm();
    expect(picker['osmVisible']()).toHaveLength(7);
  });

  it('výběr kandidáta otevře formulář s ním a založený obchod vybere a z nabídky vyřadí', () => {
    osmAtAddress.mockReturnValueOnce(of(loaded([candidate('node/1'), candidate('node/2')])));
    TestBed.tick();
    picker.openOsmCandidate(candidate('node/1'));
    expect(picker['showAddModal']()).toBe(true);
    expect(picker['osmPick']()).toEqual(candidate('node/1'));
    const selected = vi.fn();
    picker.selectedStoreChange.subscribe(selected);
    const created = { id: '9', name: 'Albert', city: 'Vzorov', country: 'CZ' } as Store;
    picker.onStoreCreated(created);
    expect(selected).toHaveBeenLastCalledWith(created);
    expect(picker['osmCandidates']().map((c) => c.osmRef)).toEqual(['node/2']);
    expect(picker['osmPick']()).toBeNull();
    picker.openNewStore();
    expect(picker['osmPick']()).toBeNull();
  });
});
