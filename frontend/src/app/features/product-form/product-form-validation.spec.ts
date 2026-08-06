import { describe, expect, it } from 'vitest';
import { impliedNetContentUom, isProductFormValid, previewUnitPrice } from './product-form-validation';

describe('isProductFormValid', () => {
  it('requires name, category and unit base', () => {
    expect(isProductFormValid('Chléb', '4', 'MASS')).toBe(true);
    expect(isProductFormValid('', '4', 'MASS')).toBe(false);
    expect(isProductFormValid('Chléb', null, 'MASS')).toBe(false);
    expect(isProductFormValid('Chléb', '4', null)).toBe(false);
  });

  it('treats whitespace-only name as invalid', () => {
    expect(isProductFormValid('   ', '4', 'MASS')).toBe(false);
  });
});

describe('impliedNetContentUom', () => {
  it('maps unit base to the server convention', () => {
    expect(impliedNetContentUom('MASS')).toBe('KG');
    expect(impliedNetContentUom('VOLUME')).toBe('L');
    expect(impliedNetContentUom('COUNT')).toBe('PCS');
  });
});

describe('previewUnitPrice', () => {
  it('divides price by net content value', () => {
    expect(previewUnitPrice(42, 1.2, false)).toBeCloseTo(35, 5);
  });

  it('returns the price itself for variable-weight goods (already priced per kg/l)', () => {
    expect(previewUnitPrice(199, null, true)).toBe(199);
  });

  it('is null without a positive price', () => {
    expect(previewUnitPrice(null, 1, false)).toBeNull();
    expect(previewUnitPrice(0, 1, false)).toBeNull();
  });

  it('is null without a positive net content value for non-variable-weight goods', () => {
    expect(previewUnitPrice(42, null, false)).toBeNull();
    expect(previewUnitPrice(42, 0, false)).toBeNull();
  });
});
