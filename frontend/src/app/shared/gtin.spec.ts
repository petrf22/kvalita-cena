import { describe, expect, it } from 'vitest';
import { isGlobalGtin, isValidGtin, normalizeCode } from './gtin';

describe('normalizeCode', () => {
  it('strips leading zeros', () => {
    expect(normalizeCode('08595000000010')).toBe('8595000000010');
  });

  it('strips non-digit characters', () => {
    expect(normalizeCode('859-500-000')).toBe('859500000');
  });

  it('keeps a single zero as zero', () => {
    expect(normalizeCode('0')).toBe('0');
    expect(normalizeCode('000')).toBe('0');
  });

  it('leaves an already-normalized code unchanged', () => {
    expect(normalizeCode('3017620422003')).toBe('3017620422003');
  });
});

describe('isValidGtin', () => {
  it.each([
    ['8594001740659', 'GTIN-13'],
    ['90169588', 'GTIN-8'],
    ['011210617001', 'GTIN-12'],
    ['08594001740659', 'GTIN-14'],
  ])('accepts %s (%s) with a matching check digit', (code) => {
    expect(isValidGtin(code)).toBe(true);
  });

  it.each(['5997321796297', '400990052165'])('rejects %s with a wrong check digit', (code) => {
    expect(isValidGtin(code)).toBe(false);
  });

  it('rejects a length GS1 does not define', () => {
    // Platný GTIN-13 bez poslední číslice — 12 číslic, ale kontrolní nesedí.
    expect(isValidGtin('859400174065')).toBe(false);
    expect(isValidGtin('1234567')).toBe(false);
    expect(isValidGtin('859400174065900')).toBe(false);
  });

  it('rejects empty input, letters and separators inside the code', () => {
    expect(isValidGtin('')).toBe(false);
    expect(isValidGtin('   ')).toBe(false);
    expect(isValidGtin('ABCDEFGHIJKLM')).toBe(false);
    expect(isValidGtin('859400174065A')).toBe(false);
    expect(isValidGtin('8594001 740659')).toBe(false);
  });

  it('tolerates surrounding whitespace from the receipt text', () => {
    expect(isValidGtin(' 8594001740659 ')).toBe(true);
  });

  it('only checks the shape, so a weighed-goods label passes', () => {
    expect(isValidGtin('2012345678903')).toBe(true);
  });
});

describe('isGlobalGtin', () => {
  it.each([
    ['8594001740659', 'GTIN-13'],
    ['90169588', 'GTIN-8'],
    ['011210617001', 'GTIN-12'],
    ['08594001740659', 'GTIN-14 obalující GTIN-13'],
    ['10859400174067', 'GTIN-14 s indikátorem balení 1'],
  ])('accepts a global code %s (%s)', (code) => {
    expect(isGlobalGtin(code)).toBe(true);
  });

  it.each([
    ['2012345678903', 'etiketa váhového zboží (20–29)'],
    ['02012345678903', 'tatáž etiketa jako GTIN-14'],
    ['0412345678903', 'prefix 04'],
    ['9812345678902', 'kupón 98'],
    ['0512345678900', 'kupón UPC 05'],
    ['12012345678900', 'GTIN-14 s indikátorem 1 nad etiketou z vah'],
    ['9912345678909', 'kupón 99'],
    ['212345678992', 'UPC-A soustava 2 → 02'],
    ['412345678903', 'UPC-A soustava 4 → 04'],
    ['02345673', 'EAN-8 začínající 0'],
    ['20123451', 'EAN-8 začínající 2'],
  ])('rejects restricted circulation %s (%s) despite a valid check digit', (code) => {
    expect(isValidGtin(code)).toBe(true);
    expect(isGlobalGtin(code)).toBe(false);
  });

  it('rejects whatever isValidGtin rejects', () => {
    expect(isGlobalGtin('5997321796297')).toBe(false);
    expect(isGlobalGtin('')).toBe(false);
    expect(isGlobalGtin('TUK.ROHLIK')).toBe(false);
  });
});
