import { UnitBase } from '../../models/catalog';

/**
 * Čistá validace/dopočty pro formulář nového zboží — mimo Angular, ať jde otestovat Vitestem
 * bez TestBed (stejný vzor jako price-chart-geometry.ts). Server (ProductCatalogService)
 * je jediný zdroj pravdy pro netContentBase — appka jen ukazuje uživateli náhled, aby věděl,
 * co se uloží, ne aby počítala něco jiného.
 */

export function isProductFormValid(name: string, categoryId: string | null, unitBase: string | null): boolean {
  return name.trim().length > 0 && !!categoryId && !!unitBase;
}

/** Server implied UOM konvence (docs/datovy-model.md): MASS→kg, VOLUME→l, COUNT→ks. */
export function impliedNetContentUom(unitBase: UnitBase): 'KG' | 'L' | 'PCS' {
  switch (unitBase) {
    case 'MASS':
      return 'KG';
    case 'VOLUME':
      return 'L';
    case 'COUNT':
      return 'PCS';
  }
}

/**
 * Náhled jednotkové ceny pro uživatele (server ji stejně dopočítá znovu z GENERATED sloupce) —
 * null, pokud gramáž není zadaná/kladná nebo se položka prodává jako váhové zboží (tam je
 * netContentBase vždy 1, cena na cedulce už je za kg/l).
 */
export function previewUnitPrice(
  priceAmount: number | null,
  netContentValue: number | null,
  isVariableWeight: boolean,
): number | null {
  if (priceAmount == null || priceAmount <= 0) return null;
  if (isVariableWeight) return priceAmount;
  if (netContentValue == null || netContentValue <= 0) return null;
  return priceAmount / netContentValue;
}
