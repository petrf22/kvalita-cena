import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { Subject, of } from 'rxjs';
import { vi } from 'vitest';
import type { ExternalProductCandidate, Product } from '../../models/catalog';
import { CountryService } from '../../services/country-service';
import { FormatService } from '../../services/format-service';
import { LanguageService } from '../../services/language-service';
import { MediaService } from '../../services/media-service';
import { ProductService } from '../../services/product-service';
import { ExistingProductMatch, ProductForm } from './product-form';

type LookupResult = {
  status: string;
  product?: Product | null;
  candidate?: ExternalProductCandidate | null;
};

/** Přístup k chráněným členům formuláře — šablona je v testu nepotřebná. */
type FormInternals = {
  code: () => string;
  name: () => string;
  existingByCode: () => { product: Product; fromInput: boolean } | null;
  offCandidate: () => ExternalProductCandidate | null;
};

const EAN = '8594001740659';
const LABEL = 'NAKL. OKURKY 680G 7 - 9 CM ZNOJMIA';
const existing = { id: '42', name: 'Okurky sterilované Znojmia 680 g' } as Product;

function candidate(names: { lang: string; name: string }[]): ExternalProductCandidate {
  return {
    code: EAN,
    names: names.map((n) => ({ ...n, source: 'OFF', editedByMe: false })),
    brandName: 'Znojmia',
    category: null,
    netContentValue: null,
    netContentUom: null,
  } as unknown as ExternalProductCandidate;
}

describe('ProductForm – kód předaný zvenku a ze skeneru', () => {
  let lookups: Map<string, Subject<LookupResult>>;
  let form: ProductForm;
  let internals: FormInternals;

  beforeEach(() => {
    lookups = new Map();
    const lookupByCode = vi.fn((code: string) => {
      const response = new Subject<LookupResult>();
      lookups.set(code, response);
      return response;
    });
    TestBed.configureTestingModule({
      providers: [
        {
          provide: ProductService,
          useValue: { categories: () => of([]), lookupByCode, suggestions: () => of([]) },
        },
        { provide: MediaService, useValue: {} },
        { provide: FormatService, useValue: {} },
        { provide: TranslocoService, useValue: {} },
        { provide: LanguageService, useValue: { lang: signal('cs') } },
        { provide: CountryService, useValue: { country: signal('CZ'), countries: signal([]) } },
      ],
    });
    form = TestBed.runInInjectionContext(() => new ProductForm());
    internals = form as unknown as FormInternals;
  });

  it('EAN z řádku účtenky, který katalog zná, nabídne existující zboží nahoře', () => {
    const matched = vi.fn<(match: ExistingProductMatch) => void>();
    form.existingMatched.subscribe(matched);

    form.barcode = EAN;
    lookups.get(EAN)!.next({ status: 'EXISTING', product: existing });

    expect(internals.code()).toBe(EAN);
    expect(internals.existingByCode()).toEqual({ product: existing, fromInput: true });
    form.useExistingByCode();
    expect(matched).toHaveBeenCalledWith({ product: existing, alias: '' });
  });

  it('po skenu nabídne existující zboží u pole kódu', () => {
    form.onScanned(EAN);
    lookups.get(EAN)!.next({ status: 'EXISTING', product: existing });

    expect(internals.existingByCode()).toEqual({ product: existing, fromInput: false });
  });

  it('ruční přepis kódu nabídku zruší a pozdní odpověď ke starému kódu ji nevrátí', () => {
    form.barcode = EAN;
    form.onCodeChange('90169588');
    lookups.get(EAN)!.next({ status: 'EXISTING', product: existing });

    expect(internals.existingByCode()).toBeNull();
    expect(internals.code()).toBe('90169588');
  });

  it('název z Open Food Facts v jazyce appky nahradí zkratku z pokladny', () => {
    // Totéž, co dělá efekt `initialName` — předvyplnění, ne psaní uživatele.
    form.onNameChange(LABEL);
    form.barcode = EAN;
    lookups.get(EAN)!.next({
      status: 'OFF_CANDIDATE',
      candidate: candidate([{ lang: 'cs', name: 'Okurky sterilované' }]),
    });

    expect(internals.name()).toBe('Okurky sterilované');
    expect(internals.offCandidate()?.code).toBe(EAN);
  });

  it('bez názvu v jazyce appky zkratka zůstane a cizí název se do pole nedosadí', () => {
    form.onNameChange(LABEL);
    form.barcode = EAN;
    lookups.get(EAN)!.next({
      status: 'OFF_CANDIDATE',
      candidate: candidate([{ lang: 'de', name: 'Gewürzgurken' }]),
    });

    expect(internals.name()).toBe(LABEL);
  });

  it('co uživatel napsal, OFF nepřepíše', () => {
    form.barcode = EAN;
    form.onNameInput('Okurky od babičky');
    lookups.get(EAN)!.next({
      status: 'OFF_CANDIDATE',
      candidate: candidate([{ lang: 'cs', name: 'Okurky sterilované' }]),
    });

    expect(internals.name()).toBe('Okurky od babičky');
  });

  it('prázdný kód se katalogu vůbec neptá', () => {
    form.barcode = '   ';
    form.barcode = null;

    expect(lookups.size).toBe(0);
    expect(internals.code()).toBe('');
  });
});
