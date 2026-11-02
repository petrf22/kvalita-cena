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

describe('ProductForm – předvyplnění z řádku účtenky a pozdní OFF', () => {
  let lookup: Subject<LookupResult>;
  let form: ProductForm;
  type ReceiptInternals = {
    applyReceiptDefaults: (defaults: {
      category?: { id: string } | null;
      categorySource?: string | null;
      netContentValue?: number | null;
      netContentUom?: string | null;
      isVariableWeight: boolean;
      netContentSource?: string | null;
    }) => void;
    onNetContentValueChosen: (value: number | null) => void;
    onVariableWeightChosen: (value: boolean) => void;
    onNetContentUomChosen: (uom: string | null) => void;
    onCategoryChosen: (id: string | null) => void;
    netContentValue: () => number | null;
    netContentUom: () => string | null;
    isVariableWeight: () => boolean;
    selectedCategoryId: () => string | null;
  };
  let internals: ReceiptInternals;

  const offWithNetContent = {
    ...candidate([{ lang: 'cs', name: 'Okurky sterilované' }]),
    category: { id: '7' },
    netContentValue: 680,
    netContentUom: 'G',
  } as unknown as ExternalProductCandidate;

  beforeEach(() => {
    lookup = new Subject<LookupResult>();
    TestBed.configureTestingModule({
      providers: [
        {
          provide: ProductService,
          useValue: {
            categories: () => of([]),
            lookupByCode: () => lookup,
            suggestions: () => of([]),
          },
        },
        { provide: MediaService, useValue: {} },
        { provide: FormatService, useValue: {} },
        { provide: TranslocoService, useValue: {} },
        { provide: LanguageService, useValue: { lang: signal('cs') } },
        { provide: CountryService, useValue: { country: signal('CZ'), countries: signal([]) } },
      ],
    });
    form = TestBed.runInInjectionContext(() => new ProductForm());
    internals = form as unknown as ReceiptInternals;
  });

  it('OFF přepíše, co jen předvyplnil řádek účtenky', () => {
    form.barcode = EAN;
    internals.applyReceiptDefaults({
      category: { id: '3' },
      categorySource: 'AI',
      netContentValue: 500,
      netContentUom: 'G',
      isVariableWeight: false,
      netContentSource: 'RECEIPT',
    });
    lookup.next({ status: 'OFF_CANDIDATE', candidate: offWithNetContent });

    expect(internals.netContentValue()).toBe(680);
    expect(internals.selectedCategoryId()).toBe('7');
  });

  it('ručně opravené číslo gramáže pozdní OFF nepřepíše', () => {
    form.barcode = EAN;
    internals.applyReceiptDefaults({
      netContentValue: 500,
      netContentUom: 'G',
      isVariableWeight: false,
      netContentSource: 'RECEIPT',
    });
    internals.onNetContentValueChosen(450);
    lookup.next({ status: 'OFF_CANDIDATE', candidate: offWithNetContent });

    expect(internals.netContentValue()).toBe(450);
    expect(internals.netContentUom()).toBe('G');
  });

  it('ručně vypnuté váhové zboží pozdní OFF nepřepne zpátky ani nevyplní', () => {
    form.barcode = EAN;
    internals.applyReceiptDefaults({ isVariableWeight: true, netContentSource: 'RECEIPT' });
    internals.onVariableWeightChosen(false);
    internals.onVariableWeightChosen(true);
    lookup.next({ status: 'OFF_CANDIDATE', candidate: offWithNetContent });

    expect(internals.isVariableWeight()).toBe(true);
  });

  it('pozdní předvyplnění z účtenky nepřepíše jednotku, kterou uživatel vrátil na nevyplněno', () => {
    internals.onNetContentUomChosen('KG');
    internals.onNetContentUomChosen(null);
    internals.onCategoryChosen('5');
    internals.onCategoryChosen(null);
    internals.applyReceiptDefaults({
      category: { id: '3' },
      categorySource: 'CATALOG',
      netContentValue: 500,
      netContentUom: 'G',
      isVariableWeight: false,
      netContentSource: 'RECEIPT',
    });

    expect(internals.netContentUom()).toBeNull();
    expect(internals.netContentValue()).toBeNull();
    expect(internals.selectedCategoryId()).toBeNull();
  });

  it('pozdní předvyplnění z účtenky znovu nezapne ručně vypnuté váhové zboží', () => {
    internals.onVariableWeightChosen(true);
    internals.onVariableWeightChosen(false);
    internals.applyReceiptDefaults({ isVariableWeight: true, netContentSource: 'RECEIPT' });

    expect(internals.isVariableWeight()).toBe(false);
  });
});
