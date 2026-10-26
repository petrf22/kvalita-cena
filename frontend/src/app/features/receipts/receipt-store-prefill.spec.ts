import { describe, expect, it } from 'vitest';
import { receiptStorePrefill, splitReceiptAddress } from './receipt-store-prefill';

describe('splitReceiptAddress', () => {
  it('rozloží ulici, PSČ a město', () => {
    expect(splitReceiptAddress('Nádražní 1, 100 00 Vzorov')).toEqual({
      street: 'Nádražní 1',
      postalCode: '100 00',
      city: 'Vzorov',
    });
  });

  it('PSČ bez mezery', () => {
    expect(splitReceiptAddress('Nádražní 1, 10000 Vzorov').postalCode).toBe('10000');
  });

  it('jen PSČ s městem', () => {
    expect(splitReceiptAddress('100 00 Praha 10')).toEqual({
      street: null,
      postalCode: '100 00',
      city: 'Praha 10',
    });
  });

  it('jen ulice, když parser PSČ nenašel', () => {
    expect(splitReceiptAddress('Nádražní 1')).toEqual({
      street: 'Nádražní 1',
      postalCode: null,
      city: null,
    });
  });

  it('čárka v ulici se nerozdělí', () => {
    expect(splitReceiptAddress('OC Galerie, Hlavní 5, 602 00 Brno')).toEqual({
      street: 'OC Galerie, Hlavní 5',
      postalCode: '602 00',
      city: 'Brno',
    });
    expect(splitReceiptAddress('OC Galerie, Hlavní 5').street).toBe('OC Galerie, Hlavní 5');
  });

  it('prázdná adresa', () => {
    const empty = { street: null, postalCode: null, city: null };
    expect(splitReceiptAddress(null)).toEqual(empty);
    expect(splitReceiptAddress('  ')).toEqual(empty);
  });
});

describe('receiptStorePrefill', () => {
  it('vezme celou hlavičku', () => {
    expect(
      receiptStorePrefill({
        storeNameRaw: ' albert ',
        storeAddressRaw: 'Nádražní 1, 100 00 Vzorov',
        storeIcoRaw: '12345678',
      }),
    ).toEqual({
      name: 'albert',
      street: 'Nádražní 1',
      postalCode: '100 00',
      city: 'Vzorov',
      ico: '12345678',
    });
  });

  it('bez hlavičky není čím předvyplnit', () => {
    expect(
      receiptStorePrefill({ storeNameRaw: null, storeAddressRaw: null, storeIcoRaw: null }),
    ).toBeNull();
    expect(
      receiptStorePrefill({ storeNameRaw: ' ', storeAddressRaw: '', storeIcoRaw: ' ' }),
    ).toBeNull();
  });
});
