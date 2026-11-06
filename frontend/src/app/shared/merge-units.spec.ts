import { describe, expect, it } from 'vitest';
import { mergeUnitsFit } from './merge-units';

/** Cena za balení neznámé velikosti — výchozí zboží z účtenky i bezkódové zboží bez gramáže. */
const packageUnit = { unitBase: 'COUNT', netContentValue: null, isVariableWeight: false } as const;
const grams = { unitBase: 'MASS', netContentValue: 150, isVariableWeight: false } as const;
const millilitres = { unitBase: 'VOLUME', netContentValue: 500, isVariableWeight: false } as const;
const perKg = { unitBase: 'MASS', netContentValue: null, isVariableWeight: true } as const;
const perLitre = { unitBase: 'VOLUME', netContentValue: null, isVariableWeight: true } as const;

describe('jednotky cen při přesunu na jiné zboží', () => {
  it('cena za balení jde na zboží bez gramáže i s pevnou gramáží', () => {
    expect(mergeUnitsFit(packageUnit, packageUnit)).toBe(true);
    expect(mergeUnitsFit(packageUnit, grams)).toBe(true);
  });

  it('cena za balení na váhové zboží nejde', () => {
    expect(mergeUnitsFit(packageUnit, perKg)).toBe(false);
  });

  it('cena za kg jde jen na zboží, jehož cena je taky za kg', () => {
    expect(mergeUnitsFit(perKg, grams)).toBe(true);
    expect(mergeUnitsFit(perKg, perKg)).toBe(true);
    expect(mergeUnitsFit(perKg, packageUnit)).toBe(false);
    expect(mergeUnitsFit(perKg, perLitre)).toBe(false);
  });

  it('zboží s gramáží nejde na zboží bez gramáže ani na jinou veličinu', () => {
    expect(mergeUnitsFit(grams, packageUnit)).toBe(false);
    expect(mergeUnitsFit(grams, millilitres)).toBe(false);
    expect(mergeUnitsFit(millilitres, perLitre)).toBe(true);
  });
});
