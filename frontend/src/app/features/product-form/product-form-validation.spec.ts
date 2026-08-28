import { describe, expect, it } from 'vitest';
import {
  changedFromOff,
  codeMatchesOffCandidate,
  impliedNetContentUom,
  isProductFormValid,
  netContentForOffSubmit,
  offCandidateDefaults,
  previewUnitPrice,
} from './product-form-validation';

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

describe('offCandidateDefaults', () => {
  it('converts grams and millilitres to the form units (kg/l)', () => {
    expect(
      offCandidateDefaults({ netContentValue: 250, netContentUom: 'G', unitBase: 'MASS' }),
    ).toMatchObject({ netContentValue: 0.25, unitBase: 'MASS' });
    expect(
      offCandidateDefaults({ netContentValue: 500, netContentUom: 'ML', unitBase: 'VOLUME' }),
    ).toMatchObject({ netContentValue: 0.5, unitBase: 'VOLUME' });
  });

  it('leaves kg/l values unchanged', () => {
    expect(
      offCandidateDefaults({ netContentValue: 1.5, netContentUom: 'KG', unitBase: 'MASS' }),
    ).toMatchObject({ netContentValue: 1.5 });
  });

  it('is null without a parseable quantity', () => {
    expect(offCandidateDefaults({}).netContentValue).toBeNull();
    expect(
      offCandidateDefaults({ netContentValue: 6, netContentUom: 'PCS' }).netContentValue,
    ).toBeNull();
  });

  it('carries name/brand/category through, null when absent', () => {
    expect(
      offCandidateDefaults({ name: 'Rama Klasik', brandName: 'Rama', category: { id: '4' } }),
    ).toMatchObject({ name: 'Rama Klasik', brandName: 'Rama', categoryId: '4' });
    expect(offCandidateDefaults({})).toMatchObject({
      name: null,
      brandName: null,
      categoryId: null,
    });
  });
});

describe('changedFromOff', () => {
  const defaults = {
    name: 'Rama Klasik',
    brandName: 'Rama',
    categoryId: '4',
    unitBase: 'MASS' as const,
    netContentValue: 0.25,
  };

  it('nulls fields that still match the OFF default', () => {
    expect(
      changedFromOff({ name: 'Rama Klasik', brandName: 'Rama', categoryId: '4' }, defaults),
    ).toEqual({ name: null, brandName: null, categoryId: null });
  });

  it('sends fields the user changed', () => {
    expect(
      changedFromOff({ name: 'Rama Light', brandName: 'Rama', categoryId: '7' }, defaults),
    ).toEqual({ name: 'Rama Light', brandName: null, categoryId: '7' });
  });
});

describe('netContentForOffSubmit', () => {
  it('nulls both value and unit when unchanged from the OFF default', () => {
    expect(netContentForOffSubmit(0.25, 'MASS', 0.25)).toEqual({
      netContentValue: null,
      netContentUom: null,
    });
  });

  it('sends both value and unit together when the user changed it', () => {
    expect(netContentForOffSubmit(0.3, 'MASS', 0.25)).toEqual({
      netContentValue: 0.3,
      netContentUom: 'KG',
    });
  });

  it('sends both when OFF had no default at all', () => {
    expect(netContentForOffSubmit(0.5, 'VOLUME', null)).toEqual({
      netContentValue: 0.5,
      netContentUom: 'L',
    });
  });

  it('nulls both when nothing was entered and OFF had no default', () => {
    expect(netContentForOffSubmit(null, 'MASS', null)).toEqual({
      netContentValue: null,
      netContentUom: null,
    });
  });
});

describe('codeMatchesOffCandidate', () => {
  it('matches regardless of leading zeros', () => {
    expect(codeMatchesOffCandidate('03017620422003', '3017620422003')).toBe(true);
  });

  it('does not match a different or cleared code', () => {
    expect(codeMatchesOffCandidate('1234567890128', '3017620422003')).toBe(false);
    expect(codeMatchesOffCandidate('', '3017620422003')).toBe(false);
  });
});
