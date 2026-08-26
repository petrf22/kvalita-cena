import { PriceKind } from '../models/catalog';
import type { ObservationPriceInput } from '../models/generated/graphql';
import { SELECTABLE_PRICE_KINDS } from './enum-labels';

/**
 * Čistá validace/dopočty pro víc cen z jedné cenovky (druh + částka) — mimo Angular, ať jde
 * otestovat Vitestem bez TestBed (stejný vzor jako product-form-validation.ts). Zrcadlí
 * backendovou dávkovou validaci v `PriceObservationService.submit()` (docs/datovy-model.md),
 * aby uživatel dostal chybu hned ve formuláři, ne až po odeslání.
 */
export interface PriceRow {
  /** Stabilní identifikátor řádku pro `@for … track` — NENÍ index (odebrání prostředního řádku by ho jinak posunulo). */
  key: number;
  priceKind: PriceKind;
  priceAmount: number | null;
  multibuyQty: number | null;
  multibuyTotal: number | null;
  /** Platnost akce (jen PROMO) — ISO datum (YYYY-MM-DD), jinak vždy null. */
  promoValidFrom: string | null;
  promoValidTo: string | null;
}

/** Dnešní datum jako ISO řetězec (YYYY-MM-DD) — pro porovnání s promoValidFrom bez časové složky. */
function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Platnost akce smí mít jen PROMO, `promoValidFrom` nesmí být v budoucnu (zapisuje se cena,
 * kterou uživatel VIDĚL v regále, ne cena z letáku — docs/rozvoj.md) a `od` nesmí být po `do`.
 * Zrcadlí PriceObservationService.validatePromoValidity na backendu.
 */
function isPromoValidityValid(row: PriceRow): boolean {
  if (row.priceKind !== 'PROMO') {
    return row.promoValidFrom == null && row.promoValidTo == null;
  }
  if (
    row.promoValidFrom != null &&
    row.promoValidTo != null &&
    row.promoValidFrom > row.promoValidTo
  ) {
    return false;
  }
  if (row.promoValidFrom != null && row.promoValidFrom > todayIso()) {
    return false;
  }
  return true;
}

export function isPriceRowValid(row: PriceRow): boolean {
  if (!isPromoValidityValid(row)) {
    return false;
  }
  if (row.priceKind === 'MULTIBUY') {
    return row.multibuyQty != null && row.multibuyQty >= 2 && row.multibuyTotal != null;
  }
  return row.priceAmount != null;
}

/** První druh ceny, který se v seznamu opakuje (v pořadí, ve kterém ho uživatel vidí podruhé), nebo null. */
export function duplicatePriceKind(rows: readonly PriceRow[]): PriceKind | null {
  const seen = new Set<PriceKind>();
  for (const row of rows) {
    if (seen.has(row.priceKind)) return row.priceKind;
    seen.add(row.priceKind);
  }
  return null;
}

export function arePriceRowsValid(rows: readonly PriceRow[]): boolean {
  return rows.length > 0 && duplicatePriceKind(rows) === null && rows.every(isPriceRowValid);
}

/**
 * Řádky → vstup pro submitObservations. U MULTIBUY se `priceAmount` NEposílá (server ho odvodí
 * z `multibuyTotal`) a naopak u ostatních druhů se neposílá `multibuyQty`/`multibuyTotal` — ať
 * server nemusí hádat, které pole platí, a formulář se dřív přepnutím druhu ceny nezanese
 * hodnotami z jiného řádku.
 */
export function toObservationPriceInputs(rows: readonly PriceRow[]): ObservationPriceInput[] {
  return rows.map((row) =>
    row.priceKind === 'MULTIBUY'
      ? {
          priceKind: row.priceKind,
          multibuyQty: row.multibuyQty,
          multibuyTotal: row.multibuyTotal,
        }
      : row.priceKind === 'PROMO'
        ? {
            priceKind: row.priceKind,
            priceAmount: row.priceAmount,
            promoValidFrom: row.promoValidFrom,
            promoValidTo: row.promoValidTo,
          }
        : { priceKind: row.priceKind, priceAmount: row.priceAmount },
  );
}

/**
 * Nabídka druhu ceny pro konkrétní řádek — vyloučí druhy použité v OSTATNÍCH řádcích, takže
 * duplicitu (`OBSERVATION_DUPLICATE_PRICE_KIND`) nejde ve formuláři vyrobit vůbec, jen ji
 * hlídá server jako pojistku pro souběh.
 */
export function availablePriceKinds(
  rows: readonly PriceRow[],
  current: PriceKind,
): readonly PriceKind[] {
  const usedByOthers = new Set(
    rows.filter((row) => row.priceKind !== current).map((row) => row.priceKind),
  );
  return SELECTABLE_PRICE_KINDS.filter((kind) => kind === current || !usedByOthers.has(kind));
}

/** Nový prázdný řádek — první nepoužitý druh ceny, ať uživatel po "+" nemusí sám přepínat pryč od duplicity. */
export function newPriceRow(key: number, rows: readonly PriceRow[]): PriceRow {
  const used = new Set(rows.map((row) => row.priceKind));
  const firstFree = SELECTABLE_PRICE_KINDS.find((kind) => !used.has(kind));
  return {
    key,
    priceKind: firstFree ?? SELECTABLE_PRICE_KINDS[0],
    priceAmount: null,
    multibuyQty: null,
    multibuyTotal: null,
    promoValidFrom: null,
    promoValidTo: null,
  };
}
