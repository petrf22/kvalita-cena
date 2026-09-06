import { describe, expect, it } from 'vitest';
import type { Product } from '../../models/catalog';
import {
  buildUpdateProductInput,
  changedNames,
  changedFromOff,
  codeMatchesOffCandidate,
  defaultNetContentUom,
  isProductFormValid,
  netContentBase,
  netContentForOffSubmit,
  netContentForUpdateSubmit,
  netContentUomFor,
  netContentUomOptions,
  visibleNetContent,
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

describe('netContentUomOptions', () => {
  /** Nabídnout u hmotnosti litry by skončilo chybou UOM_MISMATCH až při uložení. */
  it('offers only units the server accepts for the given unit base', () => {
    expect(netContentUomOptions('MASS')).toEqual(['G', 'KG']);
    expect(netContentUomOptions('VOLUME')).toEqual(['ML', 'L']);
    expect(netContentUomOptions('COUNT')).toEqual(['PCS']);
  });

  it('defaults to the smaller unit, the one usually printed on the package', () => {
    expect(defaultNetContentUom('MASS')).toBe('G');
    expect(defaultNetContentUom('VOLUME')).toBe('ML');
    expect(defaultNetContentUom('COUNT')).toBe('PCS');
  });
});

describe('netContentUomFor', () => {
  it('keeps the current choice while it still fits the unit base', () => {
    expect(netContentUomFor('MASS', 'KG')).toBe('KG');
    expect(netContentUomFor('VOLUME', 'ML')).toBe('ML');
  });

  it('falls back to the default when the unit base changed under it', () => {
    expect(netContentUomFor('VOLUME', 'G')).toBe('ML');
    expect(netContentUomFor('MASS', null)).toBe('G');
    expect(netContentUomFor('COUNT', 'KG')).toBe('PCS');
  });
});

describe('netContentBase', () => {
  /** Zrcadlo NetContentCalculator na serveru — 60 g uložených jako 0,06 kg. */
  it('converts grams and millilitres to the base unit', () => {
    expect(netContentBase(60, 'G')).toBeCloseTo(0.06, 6);
    expect(netContentBase(330, 'ML')).toBeCloseTo(0.33, 6);
  });

  it('leaves base units alone', () => {
    expect(netContentBase(1.5, 'KG')).toBe(1.5);
    expect(netContentBase(0.5, 'L')).toBe(0.5);
    expect(netContentBase(6, 'PCS')).toBe(6);
  });

  it('is null without both the value and the unit', () => {
    expect(netContentBase(null, 'G')).toBeNull();
    expect(netContentBase(60, null)).toBeNull();
  });
});

describe('visibleNetContent', () => {
  /** Past: pole gramáže je u kusového zboží skryté, ale signál si drží, co uživatel zadal
   *  ještě u hmotnosti. 60 s PCS uloží balení o 60 kusech, net_content_base má zůstat 1. */
  it('drops a quantity left over from before the switch to piece goods', () => {
    expect(
      visibleNetContent({
        unitBase: 'COUNT',
        netContentValue: 60,
        netContentUom: 'G',
        isVariableWeight: true,
      }),
    ).toEqual({ netContentValue: null, netContentUom: 'PCS', isVariableWeight: false });
  });

  it('drops the quantity for variable-weight goods (price is already per kg/l)', () => {
    expect(
      visibleNetContent({
        unitBase: 'MASS',
        netContentValue: 60,
        netContentUom: 'G',
        isVariableWeight: true,
      }),
    ).toEqual({ netContentValue: null, netContentUom: 'G', isVariableWeight: true });
  });

  it('passes a visible quantity through untouched', () => {
    expect(
      visibleNetContent({
        unitBase: 'MASS',
        netContentValue: 60,
        netContentUom: 'G',
        isVariableWeight: false,
      }),
    ).toEqual({ netContentValue: 60, netContentUom: 'G', isVariableWeight: false });
  });

  /** Jednotka se dorovná i tehdy, když ji přepnutí základní jednotky nestihlo překlopit. */
  it('repairs a unit that no longer fits the unit base', () => {
    expect(
      visibleNetContent({
        unitBase: 'VOLUME',
        netContentValue: 500,
        netContentUom: 'G',
        isVariableWeight: false,
      }),
    ).toEqual({ netContentValue: 500, netContentUom: 'ML', isVariableWeight: false });
  });
});

describe('previewUnitPrice', () => {
  it('divides price by net content converted to the base unit', () => {
    expect(previewUnitPrice(42, 1.2, 'KG', false)).toBeCloseTo(35, 5);
    // 60 g za 12 Kč je 200 Kč/kg — bez převodu by vyšlo 0,2.
    expect(previewUnitPrice(12, 60, 'G', false)).toBeCloseTo(200, 5);
  });

  it('returns the price itself for variable-weight goods (already priced per kg/l)', () => {
    expect(previewUnitPrice(199, null, 'KG', true)).toBe(199);
  });

  it('is null without a positive price', () => {
    expect(previewUnitPrice(null, 1, 'KG', false)).toBeNull();
    expect(previewUnitPrice(0, 1, 'KG', false)).toBeNull();
  });

  it('is null without a positive net content value for non-variable-weight goods', () => {
    expect(previewUnitPrice(42, null, 'KG', false)).toBeNull();
    expect(previewUnitPrice(42, 0, 'KG', false)).toBeNull();
  });
});

describe('offCandidateDefaults', () => {
  /** Uživatel má vidět „250 g" jako na obale, ne přepočtené „0,25 kg". */
  it('keeps the quantity in the unit OFF stores it in', () => {
    expect(
      offCandidateDefaults({ netContentValue: 250, netContentUom: 'G', unitBase: 'MASS' }, 'cs'),
    ).toMatchObject({ netContentValue: 250, netContentUom: 'G', unitBase: 'MASS' });
    expect(
      offCandidateDefaults({ netContentValue: 500, netContentUom: 'ML', unitBase: 'VOLUME' }, 'cs'),
    ).toMatchObject({ netContentValue: 500, netContentUom: 'ML', unitBase: 'VOLUME' });
  });

  it('keeps kg/l values in kg/l', () => {
    expect(
      offCandidateDefaults({ netContentValue: 1.5, netContentUom: 'KG', unitBase: 'MASS' }, 'cs'),
    ).toMatchObject({ netContentValue: 1.5, netContentUom: 'KG' });
  });

  it('is null without a parseable quantity', () => {
    expect(offCandidateDefaults({}, 'cs').netContentValue).toBeNull();
    expect(
      offCandidateDefaults({ netContentValue: 6, netContentUom: 'PCS' }, 'cs').netContentValue,
    ).toBeNull();
  });

  it('prefills the name only from the app language, never from another one', () => {
    // Kandidát MÁ český název — pole "Název" se předvyplní.
    expect(
      offCandidateDefaults(
        {
          name: 'Rama Klasik',
          names: [{ lang: 'cs', name: 'Rama Klasik' }],
          brandName: 'Rama',
          category: { id: '4' },
        },
        'cs',
      ),
    ).toMatchObject({ name: 'Rama Klasik', brandName: 'Rama', categoryId: '4' });

    // Magnesia z OFF: jen německý název. Pole "Název" musí zůstat PRÁZDNÉ, jinak by se
    // němčina uložila jako český název — přesně to, co se touhle změnou opravuje.
    const germanOnly = offCandidateDefaults(
      { name: 'Magnesia', nameLang: 'de', names: [{ lang: 'de', name: 'Magnesia' }] },
      'cs',
    );
    expect(germanOnly.name).toBeNull();
    expect(germanOnly.names).toEqual({ de: 'Magnesia' });

    expect(offCandidateDefaults({}, 'cs')).toMatchObject({
      name: null,
      names: {},
      brandName: null,
      categoryId: null,
    });
  });
});

describe('changedNames', () => {
  it('sends only languages the user actually changed', () => {
    const source = { de: 'Magnesia', en: 'Sparkling water' };
    expect(changedNames({ de: 'Magnesia', en: 'Fizzy water' }, source, 'cs')).toEqual([
      { lang: 'en', name: 'Fizzy water' },
    ]);
  });

  /** Poslat zpátky nezměněnou hodnotu z OFF by znamenalo zapsat cizí data do core.* (ODbL). */
  it('never echoes an unchanged OFF value back to the server', () => {
    expect(changedNames({ de: 'Magnesia' }, { de: 'Magnesia' }, 'cs')).toEqual([]);
  });

  it('skips the primary language and empty fields', () => {
    expect(changedNames({ cs: 'Minerálka', de: '   ' }, {}, 'cs')).toEqual([]);
  });
});

describe('changedFromOff', () => {
  const defaults = {
    name: 'Rama Klasik',
    names: { cs: 'Rama Klasik' },
    brandName: 'Rama',
    categoryId: '4',
    unitBase: 'MASS' as const,
    netContentValue: 250,
    netContentUom: 'G' as const,
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
  const offDefaults = { netContentValue: 250, netContentUom: 'G' as const };

  it('nulls both value and unit when unchanged from the OFF default', () => {
    expect(
      netContentForOffSubmit({ netContentValue: 250, netContentUom: 'G' }, offDefaults),
    ).toEqual({ netContentValue: null, netContentUom: null });
  });

  it('sends both value and unit together when the user changed the number', () => {
    expect(
      netContentForOffSubmit({ netContentValue: 300, netContentUom: 'G' }, offDefaults),
    ).toEqual({ netContentValue: 300, netContentUom: 'G' });
  });

  /** Přepnutí jednotky mění význam čísla — 0,25 kg vs. 250 g je stejná hmotnost, ale server
   *  by ke staré OFF hodnotě 250 přiřadil kg a spočítal 1000× víc. */
  it('sends both when the user switched the unit', () => {
    expect(
      netContentForOffSubmit({ netContentValue: 0.25, netContentUom: 'KG' }, offDefaults),
    ).toEqual({ netContentValue: 0.25, netContentUom: 'KG' });
  });

  it('sends both when OFF had no default at all', () => {
    expect(
      netContentForOffSubmit(
        { netContentValue: 500, netContentUom: 'ML' },
        {
          netContentValue: null,
          netContentUom: null,
        },
      ),
    ).toEqual({ netContentValue: 500, netContentUom: 'ML' });
  });

  it('nulls both when nothing was entered and OFF had no default', () => {
    expect(
      netContentForOffSubmit(
        { netContentValue: null, netContentUom: 'G' },
        {
          netContentValue: null,
          netContentUom: null,
        },
      ),
    ).toEqual({ netContentValue: null, netContentUom: null });
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
  it('keeps the stored quantity and its unit, same as OFF candidates', () => {
    const product = {
      name: 'Rama Klasik',
      names: [{ lang: 'cs', name: 'Rama Klasik' }],
      brand: { name: 'Rama' },
      category: { id: '4' },
      unitBase: 'MASS',
      netContentValue: 250,
      netContentUom: 'G',
      piecesInPack: null,
      isVariableWeight: false,
    } as unknown as Product;
    expect(productFormDefaults(product, 'cs')).toMatchObject({
      name: 'Rama Klasik',
      brandName: 'Rama',
      categoryId: '4',
      netContentValue: 250,
      netContentUom: 'G',
    });
  });

  it('falls back to an empty brand name when the product has none', () => {
    const product = {
      name: 'Bezznačkový chléb',
      names: [{ lang: 'cs', name: 'Bezznačkový chléb' }],
      brand: null,
      category: { id: '1' },
      unitBase: 'MASS',
      netContentValue: 1,
      netContentUom: 'KG',
      piecesInPack: null,
      isVariableWeight: false,
    } as unknown as Product;
    expect(productFormDefaults(product, 'cs').brandName).toBe('');
  });
});

describe('netContentForUpdateSubmit', () => {
  const defaults = {
    name: 'Rama Klasik',
    names: { cs: 'Rama Klasik' },
    brandName: 'Rama',
    categoryId: '4',
    unitBase: 'MASS' as const,
    netContentValue: 250,
    netContentUom: 'G' as const,
    piecesInPack: null,
    isVariableWeight: false,
  };

  it('nulls both when nothing about the quantity changed', () => {
    expect(
      netContentForUpdateSubmit(
        { netContentValue: 250, netContentUom: 'G', unitBase: 'MASS', isVariableWeight: false },
        defaults,
      ),
    ).toEqual({ netContentValue: null, netContentUom: null });
  });

  it('sends both when only the unit base changed, not the number', () => {
    expect(
      netContentForUpdateSubmit(
        { netContentValue: 250, netContentUom: 'ML', unitBase: 'VOLUME', isVariableWeight: false },
        defaults,
      ),
    ).toEqual({ netContentValue: 250, netContentUom: 'ML' });
  });

  /** Stejné číslo v jiné jednotce je jiná hmotnost — bez tohohle by se 250 kg uložilo jako 250 g. */
  it('sends both when only the unit changed, not the number', () => {
    expect(
      netContentForUpdateSubmit(
        { netContentValue: 250, netContentUom: 'KG', unitBase: 'MASS', isVariableWeight: false },
        defaults,
      ),
    ).toEqual({ netContentValue: 250, netContentUom: 'KG' });
  });

  it('sends both when the value itself changed', () => {
    expect(
      netContentForUpdateSubmit(
        { netContentValue: 300, netContentUom: 'G', unitBase: 'MASS', isVariableWeight: false },
        defaults,
      ),
    ).toEqual({ netContentValue: 300, netContentUom: 'G' });
  });

  it('sends null value but a valid unit when switching to variable weight', () => {
    expect(
      netContentForUpdateSubmit(
        { netContentValue: null, netContentUom: 'G', unitBase: 'MASS', isVariableWeight: true },
        defaults,
      ),
    ).toEqual({ netContentValue: null, netContentUom: 'G' });
  });
});

describe('buildUpdateProductInput', () => {
  const defaults = {
    name: 'Rama Klasik',
    names: { cs: 'Rama Klasik' },
    nameLang: 'cs',
    brandName: 'Rama',
    categoryId: '4',
    unitBase: 'MASS' as const,
    netContentValue: 250,
    netContentUom: 'G' as const,
    piecesInPack: 1,
    isVariableWeight: false,
  };

  it('sends null for every field left unchanged', () => {
    expect(buildUpdateProductInput({ ...defaults, names: [] }, defaults)).toEqual({
      name: null,
      nameLang: 'cs',
      names: null,
      brandName: null,
      clearBrand: false,
      categoryId: null,
      unitBase: null,
      netContentValue: null,
      netContentUom: null,
      clearNetContent: false,
      piecesInPack: null,
      clearPiecesInPack: false,
      isVariableWeight: null,
    });
  });

  /**
   * Past, kvůli které clearNetContent vzniklo: `netContentValue: null` v patchi znamená
   * "nezměněno", takže server sáhne po staré gramáži a u kusového zboží ji spočítá jako počet
   * (250 g → 250 ks). Vyprázdnění se proto musí říct vlastním příznakem.
   */
  it('asks the server to clear the quantity when the form no longer has one', () => {
    const form = {
      ...defaults,
      names: [],
      unitBase: 'COUNT' as const,
      netContentValue: null,
      netContentUom: 'PCS' as const,
    };
    const input = buildUpdateProductInput(form, defaults);
    expect(input.clearNetContent).toBe(true);
    expect(input.netContentValue).toBeNull();
  });

  it('does not clear when the quantity was empty all along', () => {
    const emptyDefaults = { ...defaults, netContentValue: null, netContentUom: null };
    const form = { ...emptyDefaults, names: [] };
    expect(buildUpdateProductInput(form, emptyDefaults).clearNetContent).toBe(false);
  });

  it('does not clear when the quantity only changed', () => {
    const form = { ...defaults, names: [], netContentValue: 300 };
    expect(buildUpdateProductInput(form, defaults).clearNetContent).toBe(false);
  });

  it('clears brand and pieces when emptied', () => {
    const form = { ...defaults, names: [], brandName: '', piecesInPack: null };
    const input = buildUpdateProductInput(form, defaults);
    expect(input.clearBrand).toBe(true);
    expect(input.brandName).toBeNull();
    expect(input.clearPiecesInPack).toBe(true);
    expect(input.piecesInPack).toBeNull();
  });

  it('sends the changed name and category', () => {
    const form = { ...defaults, names: [], name: 'Rama Light', categoryId: '7' };
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
