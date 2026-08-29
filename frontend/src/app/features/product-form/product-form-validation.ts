import type { PhotoKind } from '../../models/generated/enums';
import { NetContentUom, UnitBase } from '../../models/catalog';
import { normalizeCode } from '../../shared/gtin';

/**
 * Čistá validace/dopočty pro formulář nového zboží — mimo Angular, ať jde otestovat Vitestem
 * bez TestBed (stejný vzor jako price-chart-geometry.ts). Server (ProductCatalogService)
 * je jediný zdroj pravdy pro netContentBase — appka jen ukazuje uživateli náhled, aby věděl,
 * co se uloží, ne aby počítala něco jiného.
 */

export function isProductFormValid(
  name: string,
  categoryId: string | null,
  unitBase: string | null,
): boolean {
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

/** Minimální tvar OFF kandidáta, který formulář potřebuje — místo generovaného
 *  ProductLookupByCodeQuery typu, ať jde testovat i bez fixtur odpovídajících celému dotazu. */
export interface OffCandidateShape {
  name?: string | null;
  brandName?: string | null;
  category?: { id: string } | null;
  unitBase?: UnitBase | null;
  netContentValue?: number | null;
  netContentUom?: NetContentUom | null;
}

export interface OffCandidateDefaults {
  name: string | null;
  brandName: string | null;
  categoryId: string | null;
  unitBase: UnitBase | null;
  netContentValue: number | null;
}

/**
 * Výchozí hodnoty formuláře z OFF kandidáta pro předvyplnění. Gramáž/objem OFF nese v G/ML
 * (OffNetContentConverter na backendu), formulář vždy v kg/l (impliedNetContentUom výš) — proto
 * dělení 1000. Tenhle převedený snímek appka drží stranou (`offDefaults`) a při submitu ho
 * používá k rozhodnutí, které pole poslat serveru (CLAUDE.md, past OFF kandidáta).
 */
export function offCandidateDefaults(candidate: OffCandidateShape): OffCandidateDefaults {
  return {
    name: candidate.name ?? null,
    brandName: candidate.brandName ?? null,
    categoryId: candidate.category?.id ?? null,
    unitBase: candidate.unitBase ?? null,
    netContentValue: toFormNetContentValue(candidate.netContentValue, candidate.netContentUom),
  };
}

function toFormNetContentValue(
  value: number | null | undefined,
  uom: NetContentUom | null | undefined,
): number | null {
  if (value == null || uom == null) return null;
  switch (uom) {
    case 'G':
    case 'ML':
      return value / 1000;
    case 'KG':
    case 'L':
      return value;
    case 'PCS':
      return null;
  }
}

export interface OffTextFieldsSubmit {
  name: string | null;
  brandName: string | null;
  categoryId: string | null;
}

/**
 * Které textové/kategorie hodnoty poslat serveru vs. nechat je dál dodávat OFF (`null`) —
 * CatalogEditService.updateProduct je stejně porovná proti efektivní (OFF-doplněné) hodnotě,
 * tenhle filtr je jen pro jistotu/čitelnost, ne nutnost jako u gramáže níž.
 */
export function changedFromOff(
  current: { name: string; brandName: string; categoryId: string | null },
  defaults: OffCandidateDefaults,
): OffTextFieldsSubmit {
  return {
    name: sameOrNull(current.name.trim(), defaults.name),
    brandName: sameOrNull(current.brandName.trim() || null, defaults.brandName),
    categoryId: current.categoryId === defaults.categoryId ? null : current.categoryId,
  };
}

function sameOrNull(value: string | null, defaultValue: string | null): string | null {
  return value === defaultValue ? null : value;
}

export interface OffNetContentSubmit {
  netContentValue: number | null;
  netContentUom: 'KG' | 'L' | 'PCS' | null;
}

/**
 * Gramáž/objem pro CreateProductFromOffInput — hodnota a jednotka se MUSÍ posílat vždy jako
 * dvojice, nikdy jen jedna z nich (CLAUDE.md, past OFF kandidáta): server bez shody by spočítal
 * netContentBase ze staré OFF hodnoty (product.getNetContentValue()) spárované s novou jednotkou
 * z formuláře — u OFF gramáže v gramech vs. formuláře v kg by to dalo číslo 1000× větší. Shoda
 * s převedeným OFF defaultem (nebo nic nezadáno) → obojí `null`, ať hodnotu dál dodává OFF;
 * jinak (uživatel opravil, nebo OFF žádnou gramáž nedal) obojí z formuláře.
 */
export function netContentForOffSubmit(
  currentValue: number | null,
  unitBase: UnitBase,
  defaultValue: number | null,
): OffNetContentSubmit {
  const changed =
    currentValue != null && (defaultValue == null || Math.abs(currentValue - defaultValue) >= 1e-9);
  if (!changed) return { netContentValue: null, netContentUom: null };
  return { netContentValue: currentValue, netContentUom: impliedNetContentUom(unitBase) };
}

/**
 * Naskenovaný/zadaný kód pořád patří k nabídnutému OFF kandidátovi — jinak uživatel kód smazal
 * nebo přepsal (bezkódová položka, jiné zboží) a appka musí uložit přes createProduct, ne
 * createProductFromOff (CLAUDE.md, past OFF kandidáta).
 */
export function codeMatchesOffCandidate(code: string, candidateCode: string): boolean {
  const normalized = normalizeCode(code);
  return normalized !== '' && normalized === normalizeCode(candidateCode);
}

export interface PendingPhotoUpload {
  file: File;
  kind: PhotoKind;
}

/**
 * Které vybrané fotky nahrát po založení zboží a v jakém pořadí — obě volitelné. Fotka zboží
 * jde první, ať dostane sortOrder 0 (hlavní fotka záznamu, MediaService.upload), etiketa až
 * po ní. Nahrání samotné zajišťuje volající komponenta až PO úspěšném createProduct/
 * createProductFromOff (docs/datovy-model.md, "fotky se nahrávají výhradně na existující
 * záznam") — tahle funkce jen určuje pořadí a druh, samotný upload nespouští.
 */
export function pendingPhotoUploads(
  itemFile: File | null,
  labelFile: File | null,
): PendingPhotoUpload[] {
  const uploads: PendingPhotoUpload[] = [];
  if (itemFile) uploads.push({ file: itemFile, kind: 'ITEM' });
  if (labelFile) uploads.push({ file: labelFile, kind: 'LABEL' });
  return uploads;
}
