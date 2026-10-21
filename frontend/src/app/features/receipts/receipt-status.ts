import type { ReceiptDetail, ReceiptLine, ReceiptListItem } from '../../models/catalog';
import {
  ReceiptLineStatus,
  ReceiptProcessingStatus,
  ReceiptReviewStatus,
} from '../../models/generated/enums';
import {
  RECEIPT_PROCESSING_STATUS_KEYS,
  RECEIPT_REVIEW_STATUS_KEYS,
} from '../../shared/enum-labels';

/**
 * Stavová logika účtenky bez UI — obrazovky seznamu i detailu z ní jen čtou. Dvě osy stavu
 * (docs/datovy-model.md, „Účtenky"): co udělal stroj (`processingStatus`) a kam došel uživatel
 * s párováním (`reviewStatus`). Uživatele zajímá jen jedna věc naráz — dokud stroj nedoběhl,
 * je to zpracování, potom párování.
 */

type ReceiptStatusSource = Pick<ReceiptListItem, 'processingStatus' | 'reviewStatus'>;

/** Barvy štítku jsou stavové předvolby `nz-tag`, ne vlastní hodnoty (docs/design.md). */
export type ReceiptTagColor = 'default' | 'processing' | 'warning' | 'success' | 'error';

export function receiptStatusKey(receipt: ReceiptStatusSource): string {
  return receipt.processingStatus === ReceiptProcessingStatus.Processed
    ? RECEIPT_REVIEW_STATUS_KEYS[receipt.reviewStatus]
    : RECEIPT_PROCESSING_STATUS_KEYS[receipt.processingStatus];
}

export function receiptStatusColor(receipt: ReceiptStatusSource): ReceiptTagColor {
  switch (receipt.processingStatus) {
    case ReceiptProcessingStatus.Queued:
      return 'default';
    case ReceiptProcessingStatus.Processing:
      return 'processing';
    case ReceiptProcessingStatus.Processed:
      return receipt.reviewStatus === ReceiptReviewStatus.Done ? 'success' : 'warning';
    default:
      return 'error';
  }
}

/** Stroj ještě nedoběhl — obrazovka se má sama po chvíli zeptat znovu. */
export function isAwaitingProcessing(receipt: Pick<ReceiptListItem, 'processingStatus'>): boolean {
  return (
    receipt.processingStatus === ReceiptProcessingStatus.Queued ||
    receipt.processingStatus === ReceiptProcessingStatus.Processing
  );
}

/** Stroj skončil, ale párovat není co (není účtenka / nečitelná / selhalo) — zbývá jen smazat. */
export function isProcessingDeadEnd(receipt: Pick<ReceiptListItem, 'processingStatus'>): boolean {
  return (
    receipt.processingStatus === ReceiptProcessingStatus.NotAReceipt ||
    receipt.processingStatus === ReceiptProcessingStatus.Unreadable ||
    receipt.processingStatus === ReceiptProcessingStatus.Failed
  );
}

/** Nejlepší název obchodu, jaký appka má: potvrzený obchod, jinak text z hlavičky. */
export function receiptStoreName(
  receipt: Pick<ReceiptListItem, 'store' | 'storeNameRaw'>,
): string | null {
  return receipt.store?.name ?? (receipt.storeNameRaw?.trim() || null);
}

/**
 * Z řádku už vznikla cena — pak ho server nedovolí přepárovat ani ignorovat
 * (`RECEIPT_LINE_ALREADY_CONFIRMED`), cena by jinak visela u jiného zboží než řádek.
 * Podle `confirmed`, ne `observationId`: tu po 180 dnech maže pseudonymizace (docs/soukromi.md).
 */
export function isLineLocked(line: Pick<ReceiptLine, 'confirmed'>): boolean {
  return line.confirmed;
}

/** Řádek jde zaškrtnout k hromadnému potvrzení: má zboží a cena z něj ještě nevznikla. */
export function isLineConfirmable(line: Pick<ReceiptLine, 'status' | 'confirmed'>): boolean {
  return line.status === ReceiptLineStatus.Suggested && !isLineLocked(line);
}

/** Výchozí zaškrtnutí — přesně řádky, které server (nebo uživatel) připravil jako návrh. */
export function defaultSelectedLineIds(lines: readonly ReceiptLine[]): Set<string> {
  return new Set(lines.filter(isLineConfirmable).map((line) => line.id));
}

/** Proč hromadné potvrzení teď nejde — klíč do bundlu `receipts`, nebo `null`, když jde. */
export type ConfirmBlocker = 'notProcessed' | 'storeMissing' | 'purchaseTimeMissing' | 'nothing';

export function confirmBlocker(
  receipt: Pick<ReceiptDetail, 'processingStatus' | 'store' | 'purchasedAt'>,
  selectedCount: number,
): ConfirmBlocker | null {
  if (receipt.processingStatus !== ReceiptProcessingStatus.Processed) return 'notProcessed';
  if (!receipt.store) return 'storeMissing';
  if (!receipt.purchasedAt) return 'purchaseTimeMissing';
  if (selectedCount === 0) return 'nothing';
  return null;
}

/** Kolize se stejnou cenou zapsanou týž den (ReceiptReviewService.SKIP_DUPLICATE). */
export const SKIP_DUPLICATE_SAME_DAY = 'DUPLICATE_SAME_DAY';

/**
 * Překladový klíč důvodu přeskočení: duplicita má vlastní text, ostatní důvody jsou kódy
 * `ErrorCode` a berou se z kontraktu chyb (`errors.*`, docs/lokalizace.md).
 */
export function skipReasonKey(reason: string): string {
  return reason === SKIP_DUPLICATE_SAME_DAY ? 'receipts.skip.duplicateSameDay' : `errors.${reason}`;
}

/** Nahradí řádek v detailu účtenky jeho novou verzí z mutace (match/ignore/unmatch). */
export function replaceLine(receipt: ReceiptDetail, line: ReceiptLine): ReceiptDetail {
  return {
    ...receipt,
    lines: receipt.lines.map((existing) => (existing.id === line.id ? line : existing)),
  };
}

/** Rychlý přehled pro hlavičku seznamu řádků: kolik čeká, kolik je připravené, kolik zapsané. */
export interface LineSummary {
  total: number;
  open: number;
  ready: number;
  recorded: number;
}

export function lineSummary(lines: readonly ReceiptLine[]): LineSummary {
  return {
    total: lines.length,
    open: lines.filter((l) => l.status === ReceiptLineStatus.Unmatched).length,
    ready: lines.filter(isLineConfirmable).length,
    recorded: lines.filter(isLineLocked).length,
  };
}

/**
 * `Date` pro `nz-date-picker` a zpět do ISO — čas nákupu je okamžik, takže se posílá celý
 * s pásmem (`toISOString`), ne jen den jako u `observedAt` v ručním zápisu ceny.
 */
export function purchasedAtToDate(iso: string | null | undefined): Date | null {
  if (!iso) return null;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}

export function samePurchaseTime(a: Date | null, iso: string | null | undefined): boolean {
  const b = purchasedAtToDate(iso);
  if (a === null || b === null) return a === b;
  return a.getTime() === b.getTime();
}

/**
 * Zaškrtnutí po změně řádků (mutace, obnova ze serveru): řádek, který se NOVĚ stal
 * potvrditelným (uživatel ho právě spároval, server přidal návrh), se zaškrtne; řádek, který
 * potvrditelný být přestal, se odškrtne. Co si uživatel u beze změny potvrditelných řádků
 * odškrtl, zůstane odškrtnuté.
 */
export function nextSelection(
  selected: ReadonlySet<string>,
  previousLines: readonly ReceiptLine[],
  nextLines: readonly ReceiptLine[],
): Set<string> {
  const wasConfirmable = new Set(previousLines.filter(isLineConfirmable).map((line) => line.id));
  const result = new Set<string>();
  for (const line of nextLines) {
    if (!isLineConfirmable(line)) continue;
    if (!wasConfirmable.has(line.id) || selected.has(line.id)) result.add(line.id);
  }
  return result;
}

/**
 * Verze textu souhlasu, který web SÁM ukazuje (`receipts.upload.consentProcessing` /
 * `consentDetail`). Posílá se tahle, ne číslo ze serveru — jinak by stará otevřená stránka po
 * zvednutí `app.receipt.consent-version` odeslala souhlas s textem, který uživatel neviděl
 * (docs/soukromi.md, „Účtenky"). Při změně textu zvednout spolu se serverem.
 */
export const RECEIPT_CONSENT_VERSION = 1;

/** Server chce jiné znění souhlasu, než jaké tahle verze webu umí ukázat — pomůže obnovit stránku. */
export function consentOutdated(serverVersion: number | null): boolean {
  return serverVersion !== null && serverVersion !== RECEIPT_CONSENT_VERSION;
}
