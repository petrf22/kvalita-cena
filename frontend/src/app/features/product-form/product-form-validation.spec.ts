import { describe, expect, it } from 'vitest';
import type { Product } from '../../models/catalog';
import {
  buildUpdateProductInput,
  changedFromOff,
  codeMatchesOffCandidate,
  impliedNetContentUom,
  isProductFormValid,
  netContentForOffSubmit,
  netContentForUpdateSubmit,
  offCandidateDefaults,
  pendingPhotoUploads,
  previewUnitPrice,
  productFormDefaults,
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

describe('productFormDefaults', () => {
  it('converts grams/millilitres to form units (kg/l), same as OFF candidates', () => {
    const product = {
      name: 'Rama Klasik',
      brand: { name: 'Rama' },
      category: { id: '4' },
      unitBase: 'MASS',
      netContentValue: 250,
      netContentUom: 'G',
      piecesInPack: null,
      isVariableWeight: false,
    } as unknown as Product;
    expect(productFormDefaults(product)).toMatchObject({
      name: 'Rama Klasik',
      brandName: 'Rama',
      categoryId: '4',
      netContentValue: 0.25,
    });
  });

  it('falls back to an empty brand name when the product has none', () => {
    const product = {
      name: 'Bezznačkový chléb',
      brand: null,
      category: { id: '1' },
      unitBase: 'MASS',
      netContentValue: 1,
      netContentUom: 'KG',
      piecesInPack: null,
      isVariableWeight: false,
    } as unknown as Product;
    expect(productFormDefaults(product).brandName).toBe('');
  });
});

describe('netContentForUpdateSubmit', () => {
  const defaults = {
    name: 'Rama Klasik',
    brandName: 'Rama',
    categoryId: '4',
    unitBase: 'MASS' as const,
    netContentValue: 0.25,
    piecesInPack: null,
    isVariableWeight: false,
  };

  it('nulls both when nothing about the quantity changed', () => {
    expect(
      netContentForUpdateSubmit(
        { netContentValue: 0.25, unitBase: 'MASS', isVariableWeight: false },
        defaults,
      ),
    ).toEqual({ netContentValue: null, netContentUom: null });
  });

  it('sends both when only the unit base changed, not the number', () => {
    expect(
      netContentForUpdateSubmit(
        { netContentValue: 0.25, unitBase: 'VOLUME', isVariableWeight: false },
        defaults,
      ),
    ).toEqual({ netContentValue: 0.25, netContentUom: 'L' });
  });

  it('sends both when the value itself changed', () => {
    expect(
      netContentForUpdateSubmit(
        { netContentValue: 0.3, unitBase: 'MASS', isVariableWeight: false },
        defaults,
      ),
    ).toEqual({ netContentValue: 0.3, netContentUom: 'KG' });
  });

  it('sends null value when switching to variable weight', () => {
    expect(
      netContentForUpdateSubmit(
        { netContentValue: null, unitBase: 'MASS', isVariableWeight: true },
        defaults,
      ),
    ).toEqual({ netContentValue: null, netContentUom: 'KG' });
  });
});

describe('buildUpdateProductInput', () => {
  const defaults = {
    name: 'Rama Klasik',
    brandName: 'Rama',
    categoryId: '4',
    unitBase: 'MASS' as const,
    netContentValue: 0.25,
    piecesInPack: 1,
    isVariableWeight: false,
  };

  it('sends null for every field left unchanged', () => {
    expect(buildUpdateProductInput(defaults, defaults)).toEqual({
      name: null,
      brandName: null,
      clearBrand: false,
      categoryId: null,
      unitBase: null,
      netContentValue: null,
      netContentUom: null,
      piecesInPack: null,
      clearPiecesInPack: false,
      isVariableWeight: null,
    });
  });

  it('clears brand and pieces when emptied', () => {
    const form = { ...defaults, brandName: '', piecesInPack: null };
    const input = buildUpdateProductInput(form, defaults);
    expect(input.clearBrand).toBe(true);
    expect(input.brandName).toBeNull();
    expect(input.clearPiecesInPack).toBe(true);
    expect(input.piecesInPack).toBeNull();
  });

  it('sends the changed name and category', () => {
    const form = { ...defaults, name: 'Rama Light', categoryId: '7' };
    const input = buildUpdateProductInput(form, defaults);
    expect(input.name).toBe('Rama Light');
    expect(input.categoryId).toBe('7');
  });
});

describe('pendingPhotoUploads', () => {
  const itemFile = new File(['x'], 'zbozi.jpg', { type: 'image/jpeg' });
  const labelFile = new File(['y'], 'etiketa.jpg', { type: 'image/jpeg' });

  it('is empty when neither photo was picked', () => {
    expect(pendingPhotoUploads(null, null)).toEqual([]);
  });

  it('sends only the item photo when the label was not picked', () => {
    expect(pendingPhotoUploads(itemFile, null)).toEqual([{ file: itemFile, kind: 'ITEM' }]);
  });

  it('sends only the label photo when the item was not picked', () => {
    expect(pendingPhotoUploads(null, labelFile)).toEqual([{ file: labelFile, kind: 'LABEL' }]);
  });

  it('puts the item photo first so it becomes the main photo (sortOrder 0)', () => {
    expect(pendingPhotoUploads(itemFile, labelFile)).toEqual([
      { file: itemFile, kind: 'ITEM' },
      { file: labelFile, kind: 'LABEL' },
    ]);
  });
});
