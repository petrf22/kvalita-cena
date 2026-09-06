import type { PhotoKind } from '../../models/generated/enums';
import { NetContentUom, Product, UnitBase, UpdateProductInput } from '../../models/catalog';
import { normalizeCode } from '../../shared/gtin';

/** Název v jednom jazyce, jak ho vrací API (`Product.names`, `ExternalProductCandidate.names`). */
export interface NameInLanguage {
  lang?: string | null;
  name: string;
}

export interface ProductNameSubmit {
  lang: string;
  name: string;
}

/**
 * Názvy po jazycích na mapu jazyk→název. Položky bez jazyka se zahazují — u „hlavního" názvu
 * z OFF se jazyk poznat nedá (docs/lokalizace.md) a formulář by ho neměl kam zařadit.
 */
export function namesByLang(
  names: readonly NameInLanguage[] | null | undefined,
): Record<string, string> {
  const result: Record<string, string> = {};
  for (const entry of names ?? []) {
    if (entry.lang) result[entry.lang] = entry.name;
  }
  return result;
}

/**
 * Které z ostatních jazyků poslat serveru: jen ty, jejichž text se liší od zdrojové hodnoty.
 * U OFF kandidáta je to podmínka, ne optimalizace — poslat zpátky nezměněný název z OFF by
 * znamenalo zapsat cizí data do `core.product_name`, což ODbL share-alike zakazuje
 * (CLAUDE.md, past OFF kandidáta).
 */
export function changedNames(
  current: Record<string, string>,
  source: Record<string, string>,
  excludeLang: string,
): ProductNameSubmit[] {
  return Object.entries(current)
    .filter(
      ([lang, name]) => lang !== excludeLang && name.trim() !== '' && name.trim() !== source[lang],
    )
    .map(([lang, name]) => ({ lang, name: name.trim() }));
}

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

/** Jednotka, ve které uživatel zadává gramáž/objem — to, co je na obalu. */
export type NetContentUomChoice = 'G' | 'KG' | 'ML' | 'L' | 'PCS';

/**
 * Jednotky nabídnuté ve formuláři pro danou základní jednotku, v pořadí nabídky. Množina musí
 * sedět na `NetContentCalculator.validateUomMatchesUnitBase` na serveru — nabídnout u MASS
 * litry by znamenalo UOM_MISMATCH až při uložení.
 */
export function netContentUomOptions(unitBase: UnitBase): readonly NetContentUomChoice[] {
  switch (unitBase) {
    case 'MASS':
      return ['G', 'KG'];
    case 'VOLUME':
      return ['ML', 'L'];
    case 'COUNT':
      return ['PCS'];
  }
}

/**
 * První z nabídky, tedy menší jednotka (g/ml) — většina obalů nese „60 g“ nebo „330 ml“ a
 * uživatel má opisovat, ne přepočítávat. Kilogramy/litry jsou pak jedno kliknutí vedle.
 */
export function defaultNetContentUom(unitBase: UnitBase): NetContentUomChoice {
  return netContentUomOptions(unitBase)[0];
}

/**
 * Jednotka po přepnutí základní jednotky — současnou volbu nechá být, dokud pro nový `unitBase`
 * dává smysl (MASS→VOLUME musí překlopit g na ml), jinak spadne na výchozí.
 */
export function netContentUomFor(
  unitBase: UnitBase,
  current: NetContentUomChoice | null,
): NetContentUomChoice {
  const options = netContentUomOptions(unitBase);
  return current != null && options.includes(current) ? current : options[0];
}

/**
 * Přepočet na základní jednotku (kg/l/ks) — zrcadlo `NetContentCalculator` na serveru, který
 * je pro `net_content_base` jediný zdroj pravdy. Appka ho potřebuje jen na náhled jednotkové
 * ceny, nikdy neposlá přepočtenou hodnotu místo zadané.
 */
export function netContentBase(
  value: number | null,
  uom: NetContentUomChoice | null,
): number | null {
  if (value == null || uom == null) return null;
  switch (uom) {
    case 'G':
    case 'ML':
      return value / 1000;
    case 'KG':
    case 'L':
    case 'PCS':
      return value;
  }
}

export interface NetContentFormState {
  unitBase: UnitBase;
  netContentValue: number | null;
  netContentUom: NetContentUomChoice;
  isVariableWeight: boolean;
}

export interface VisibleNetContent {
  netContentValue: number | null;
  netContentUom: NetContentUomChoice;
  isVariableWeight: boolean;
}

/**
 * Gramáž/objem a příznak váhového zboží očištěné o to, co formulář právě neukazuje — jediná
 * cesta, kterou tahle trojice smí odejít do serveru.
 *
 * Pole se totiž skrývají (u kusového zboží obojí, u váhového číslo), ale signály si hodnotu
 * drží dál. Bez tohohle by do serveru dorazilo číslo, které uživatel zadal ještě u hmotnosti
 * a pak přepnul na kusy: 60 spárovaných s `PCS` uloží balení o 60 kusech, protože
 * `NetContentCalculator` u COUNT bere hodnotu rovnou jako počet — místo aby `net_content_base`
 * zůstalo 1. Váhové zboží se stejným způsobem drží na prázdné gramáži (cena je za kg/l).
 */
export function visibleNetContent(state: NetContentFormState): VisibleNetContent {
  if (state.unitBase === 'COUNT') {
    return { netContentValue: null, netContentUom: 'PCS', isVariableWeight: false };
  }
  return {
    netContentValue: state.isVariableWeight ? null : state.netContentValue,
    netContentUom: netContentUomFor(state.unitBase, state.netContentUom),
    isVariableWeight: state.isVariableWeight,
  };
}

/**
 * Náhled jednotkové ceny pro uživatele (server ji stejně dopočítá znovu z GENERATED sloupce) —
 * null, pokud gramáž není zadaná/kladná nebo se položka prodává jako váhové zboží (tam je
 * netContentBase vždy 1, cena na cedulce už je za kg/l). Gramáž chodí v jednotce z formuláře
 * (60 g), jednotková cena je ale vždy za kg/l — proto `netContentBase` uprostřed.
 */
export function previewUnitPrice(
  priceAmount: number | null,
  netContentValue: number | null,
  netContentUom: NetContentUomChoice | null,
  isVariableWeight: boolean,
): number | null {
  if (priceAmount == null || priceAmount <= 0) return null;
  if (isVariableWeight) return priceAmount;
  const base = netContentBase(netContentValue, netContentUom);
  if (base == null || base <= 0) return null;
  return priceAmount / base;
}

/** Minimální tvar OFF kandidáta, který formulář potřebuje — místo generovaného
 *  ProductLookupByCodeQuery typu, ať jde testovat i bez fixtur odpovídajících celému dotazu. */
export interface OffCandidateShape {
  name?: string | null;
  nameLang?: string | null;
  names?: readonly NameInLanguage[] | null;
  brandName?: string | null;
  category?: { id: string } | null;
  unitBase?: UnitBase | null;
  netContentValue?: number | null;
  netContentUom?: NetContentUom | null;
}

export interface OffCandidateDefaults {
  name: string | null;
  /** Názvy z OFF po jazycích — zdroj pro sekci ostatních jazyků i pro diff při submitu. */
  names: Record<string, string>;
  brandName: string | null;
  categoryId: string | null;
  unitBase: UnitBase | null;
  netContentValue: number | null;
  /** Jednotka, ve které gramáž drží OFF — formulář ji přebírá, ať uživatel vidí „250 g" tak,
   *  jak je na obale, a ne přepočtené „0,25 kg". */
  netContentUom: NetContentUomChoice | null;
}

/**
 * Výchozí hodnoty formuláře z OFF kandidáta pro předvyplnění. Gramáž/objem se přebírá i s
 * jednotkou, jak ji OFF nese (typicky G/ML, `OffNetContentConverter` na backendu) — nic se
 * nepřepočítává. Tenhle snímek appka drží stranou (`offDefaults`) a při submitu ho používá
 * k rozhodnutí, které pole poslat serveru (CLAUDE.md, past OFF kandidáta).
 */
export function offCandidateDefaults(
  candidate: OffCandidateShape,
  lang: string,
): OffCandidateDefaults {
  const names = namesByLang(candidate.names);
  return {
    // Do pole "Název" patří jen název v jazyce appky. Když ho OFF nemá, zůstane pole PRÁZDNÉ
    // a cizojazyčná varianta se ukáže v sekci ostatních jazyků — předvyplnit ho německým
    // textem by znamenalo uložit němčinu jako český název (přesně to, co se opravuje).
    name: names[lang] ?? null,
    names,
    brandName: candidate.brandName ?? null,
    categoryId: candidate.category?.id ?? null,
    unitBase: candidate.unitBase ?? null,
    ...toFormNetContent(candidate.netContentValue, candidate.netContentUom),
  };
}

export interface FormNetContent {
  netContentValue: number | null;
  netContentUom: NetContentUomChoice | null;
}

/**
 * Gramáž/objem ze serveru do polí formuláře — beze změny čísla, jen s jednotkou vedle. Sdílí ji
 * prefill z OFF kandidáta i prefill z existujícího zboží (editace). Kusy do pole gramáže
 * nepatří (formulář ho u COUNT vůbec neukazuje), proto u PCS nechá obojí prázdné.
 */
export function toFormNetContent(
  value: number | null | undefined,
  uom: NetContentUom | null | undefined,
): FormNetContent {
  if (value == null || uom == null || uom === 'PCS') {
    return { netContentValue: null, netContentUom: null };
  }
  return { netContentValue: value, netContentUom: uom };
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
  netContentUom: NetContentUomChoice | null;
}

/**
 * Gramáž/objem pro CreateProductFromOffInput — hodnota a jednotka se MUSÍ posílat vždy jako
 * dvojice, nikdy jen jedna z nich (CLAUDE.md, past OFF kandidáta): server bez shody by spočítal
 * netContentBase ze staré OFF hodnoty (product.getNetContentValue()) spárované s novou jednotkou
 * z formuláře — 250 g vs. 0,25 kg by dalo číslo 1000× větší. Shoda s OFF defaultem v hodnotě
 * I jednotce (nebo nic nezadáno) → obojí `null`, ať hodnotu dál dodává OFF; jinak (uživatel
 * opravil číslo nebo přepnul jednotku, nebo OFF žádnou gramáž nedal) obojí z formuláře.
 */
export function netContentForOffSubmit(
  current: FormNetContent,
  defaults: FormNetContent,
): OffNetContentSubmit {
  if (current.netContentValue == null || current.netContentUom == null) {
    return { netContentValue: null, netContentUom: null };
  }
  const changed =
    defaults.netContentValue == null ||
    current.netContentUom !== defaults.netContentUom ||
    Math.abs(current.netContentValue - defaults.netContentValue) >= 1e-9;
  if (!changed) return { netContentValue: null, netContentUom: null };
  return { netContentValue: current.netContentValue, netContentUom: current.netContentUom };
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

export interface ProductFormDefaults {
  name: string;
  /** Všechny známé názvy po jazycích (i ty z OFF) — zdroj pro diff, viz `changedNames`. */
  names: Record<string, string>;
  brandName: string;
  categoryId: string | null;
  unitBase: UnitBase;
  netContentValue: number | null;
  netContentUom: NetContentUomChoice | null;
  piecesInPack: number | null;
  isVariableWeight: boolean;
}

/**
 * Prefill formuláře v režimu editace existujícího zboží — zrcadlo `offCandidateDefaults`, jen
 * zdroj je `Product` (z detailu), ne OFF kandidát. Gramáž/objem se přebírá i s uloženou
 * jednotkou (u zboží založeného před volbou jednotky to bude KG/L, u novějšího G/ML) — příště
 * ji uživatel vidí přesně tak, jak ji zadal.
 */
export function productFormDefaults(product: Product, lang: string): ProductFormDefaults {
  const names = namesByLang(product.names);
  return {
    // Stejné pravidlo jako u OFF kandidáta: pole "Název" je vždy v jazyce appky. Product.name
    // sem nejde dosadit naslepo — může to být fallback z jiného jazyka (viz Product.nameLang).
    name: names[lang] ?? '',
    names,
    brandName: product.brand?.name ?? '',
    categoryId: product.category?.id ?? null,
    unitBase: product.unitBase,
    ...toFormNetContent(product.netContentValue, product.netContentUom),
    piecesInPack: product.piecesInPack ?? null,
    isVariableWeight: product.isVariableWeight,
  };
}

export interface NetContentUpdateSubmit {
  netContentValue: number | null;
  netContentUom: NetContentUomChoice | null;
}

/**
 * Gramáž/objem pro UpdateProductInput — MUSÍ se posílat vždy jako dvojice, i když se změnil jen
 * `unitBase`/`isVariableWeight` (CatalogEditService.updateProduct přepočítává netContentBase
 * v jediném bloku podmíněném tím, že aspoň jedno z trojice netContentValue/netContentUom/
 * isVariableWeight přišlo nenulové — samotný unitBase by netContentBase pro novou jednotku
 * nedopočítal). Shoda s prefillem (nebo nic nezadáno) → obojí `null`, ať server nevytvoří
 * zbytečný patch; jinak (cokoli z trojice se změnilo) obojí z formuláře.
 */
export function netContentForUpdateSubmit(
  current: {
    netContentValue: number | null;
    netContentUom: NetContentUomChoice | null;
    unitBase: UnitBase;
    isVariableWeight: boolean;
  },
  defaults: ProductFormDefaults,
): NetContentUpdateSubmit {
  const changed =
    current.unitBase !== defaults.unitBase ||
    current.isVariableWeight !== defaults.isVariableWeight ||
    current.netContentUom !== defaults.netContentUom ||
    (current.netContentValue == null) !== (defaults.netContentValue == null) ||
    (current.netContentValue != null &&
      defaults.netContentValue != null &&
      Math.abs(current.netContentValue - defaults.netContentValue) >= 1e-9);
  if (!changed) return { netContentValue: null, netContentUom: null };
  return {
    netContentValue: current.isVariableWeight ? null : current.netContentValue,
    // Jednotka musí dorazit i u váhového zboží (hodnota je tam null) — server podle ní ověřuje
    // shodu se základní jednotkou a bez ní by netContentBase nepřepočítal.
    netContentUom: netContentUomFor(current.unitBase, current.netContentUom),
  };
}

export interface ProductFormState {
  name: string;
  /** Jazyk pole „Název" — vždy jazyk appky, server ho nikdy nehádá z textu. */
  nameLang: string;
  /** Názvy v OSTATNÍCH jazycích, už profiltrované přes `changedNames`. */
  names: ProductNameSubmit[];
  brandName: string;
  categoryId: string | null;
  unitBase: UnitBase;
  netContentValue: number | null;
  netContentUom: NetContentUomChoice | null;
  piecesInPack: number | null;
  isVariableWeight: boolean;
}

/**
 * `UpdateProductInput` z aktuálního stavu formuláře proti prefillu — zrcadlo
 * `buildUpdateInput()` ve `shared/store-form.ts`. Pole beze změny se posílají jako `null`
 * (patch nad core.product_user_edit je jinak zbytečně široký), vyprázdnění pošle `clear*`.
 */
export function buildUpdateProductInput(
  form: ProductFormState,
  defaults: ProductFormDefaults,
): UpdateProductInput {
  const netContent = netContentForUpdateSubmit(form, defaults);
  const trimmedBrand = form.brandName.trim();
  return {
    name: form.name.trim() === defaults.name ? null : form.name.trim(),
    nameLang: form.nameLang,
    names: form.names.length > 0 ? form.names : null,
    brandName: trimmedBrand === '' || trimmedBrand === defaults.brandName ? null : trimmedBrand,
    clearBrand: trimmedBrand === '' && defaults.brandName !== '',
    categoryId: form.categoryId === defaults.categoryId ? null : form.categoryId,
    unitBase: form.unitBase === defaults.unitBase ? null : form.unitBase,
    netContentValue: netContent.netContentValue,
    netContentUom: netContent.netContentUom,
    piecesInPack: form.piecesInPack === defaults.piecesInPack ? null : form.piecesInPack,
    clearPiecesInPack: form.piecesInPack == null && defaults.piecesInPack != null,
    isVariableWeight:
      form.isVariableWeight === defaults.isVariableWeight ? null : form.isVariableWeight,
  };
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
