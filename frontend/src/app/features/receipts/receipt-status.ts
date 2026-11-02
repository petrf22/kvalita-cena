import type { ReceiptDetail, ReceiptLine, ReceiptListItem } from '../../models/catalog';
import {
  ReceiptLineStatus,
  ReceiptMatchSource,
  ReceiptProcessingStatus,
  ReceiptReviewStatus,
} from '../../models/generated/enums';
import {
  RECEIPT_MATCH_SOURCE_KEYS,
  RECEIPT_PROCESSING_STATUS_KEYS,
  RECEIPT_REVIEW_STATUS_KEYS,
} from '../../shared/enum-labels';
import { isGlobalGtin } from '../../shared/gtin';

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

/**
 * Návrh je jen odhad podle podobnosti názvu (`FUZZY`), ne jistá shoda kódu nebo naučeného
 * označení. Potvrdit ho jde, ale jen vědomě: potvrzený odhad se naučí jako označení a zboží
 * dostane EAN z řádku, takže hromadné potvrzení omylem by chybu rozšířilo (docs/ai.md).
 */
export function isLineGuess(
  line: Pick<ReceiptLine, 'status' | 'confirmed' | 'matchSource'>,
): boolean {
  return isLineConfirmable(line) && line.matchSource === ReceiptMatchSource.Fuzzy;
}

/** Řádek appka smí zaškrtnout sama: potvrditelný návrh, který není jen odhad. */
function isLinePreselectable(
  line: Pick<ReceiptLine, 'status' | 'confirmed' | 'matchSource'>,
): boolean {
  return isLineConfirmable(line) && !isLineGuess(line);
}

/**
 * Název řádku pro oči: čitelný název od workeru („Rohlík 43g"), jinak syrový tisk
 * („ROHLÍK43GR") — starší účtenky čitelný název nemají. Párování se učí vždy podle syrového.
 */
export function lineLabel(line: Pick<ReceiptLine, 'labelRaw' | 'labelDisplay'>): string {
  return line.labelDisplay?.trim() || line.labelRaw;
}

/** Syrový tisk z účtenky, když se od zobrazeného názvu liší (k porovnání s obrázkem) — jinak `null`. */
export function lineRawLabel(line: Pick<ReceiptLine, 'labelRaw' | 'labelDisplay'>): string | null {
  return lineLabel(line) === line.labelRaw ? null : line.labelRaw;
}

/**
 * EAN řádku, pokud ho účtenka tiskne — jinak `null`. `internalCode` nese i vnitroobchodní kód
 * (platí jen v rámci řetězce) nebo etiketu váhového zboží s cenou uvnitř; jako čárový kód se
 * smí použít, předvyplnit do nového zboží i ukázat jako „EAN" jen globální GTIN
 * (`isGlobalGtin`, stejné pravidlo jako server).
 */
export function lineGtin(line: Pick<ReceiptLine, 'internalCode'>): string | null {
  const code = line.internalCode?.trim() ?? '';
  return isGlobalGtin(code) ? code : null;
}

/**
 * Popisek, podle čeho server zboží navrhl. U `CODE` s EANem na řádku řekne „podle EAN" —
 * uživatel ho vidí hned nad tím a může ho porovnat s obalem; vnitroobchodní kód zůstává
 * u obecného „podle kódu".
 */
export function matchSourceKey(
  line: Pick<ReceiptLine, 'matchSource' | 'internalCode'>,
): string | null {
  if (!line.matchSource) return null;
  if (line.matchSource === ReceiptMatchSource.Code && lineGtin(line)) {
    return 'receipts.line.matchedByEan';
  }
  return RECEIPT_MATCH_SOURCE_KEYS[line.matchSource];
}

/**
 * Výchozí zaškrtnutí — řádky, které server (nebo uživatel) připravil jako návrh. Odhad
 * (`isLineGuess`) ne: ten zaškrtává jen uživatel sám, až zboží zkontroluje.
 */
export function defaultSelectedLineIds(lines: readonly ReceiptLine[]): Set<string> {
  return new Set(lines.filter(isLinePreselectable).map((line) => line.id));
}

/**
 * Návrh ze zboží, které zná jen Open Food Facts (`OFF`) — zboží zatím neexistuje (`product`
 * je null, náhled je v `offCandidate`), založí se potvrzením řádku. Jistý jako shoda EAN.
 */
export function isLineOffSuggestion(
  line: Pick<ReceiptLine, 'status' | 'confirmed' | 'matchSource'>,
): boolean {
  return isLineConfirmable(line) && line.matchSource === ReceiptMatchSource.Off;
}

/** Skupiny seznamu řádků — od toho, co potřebuje člověka, po hotové (zrcadlo mobilního `LineGroup`). */
export type LineGroup = 'toResolve' | 'toCheck' | 'ready' | 'done' | 'ignored';

export const LINE_GROUPS: readonly LineGroup[] = [
  'toResolve',
  'toCheck',
  'ready',
  'done',
  'ignored',
];

type GroupSource = Pick<ReceiptLine, 'status' | 'confirmed' | 'matchSource'>;

export function lineGroup(line: GroupSource): LineGroup {
  if (line.status === ReceiptLineStatus.Ignored) return 'ignored';
  if (line.status === ReceiptLineStatus.Matched || line.confirmed) return 'done';
  if (isLineGuess(line)) return 'toCheck';
  if (isLineConfirmable(line)) return 'ready';
  return 'toResolve';
}

/** Řádky ve skupinách v pořadí `LINE_GROUPS`, prázdné vynechané, pořadí z účtenky uvnitř. */
export function groupedLines<T extends GroupSource>(
  lines: readonly T[],
): { group: LineGroup; lines: T[] }[] {
  return LINE_GROUPS.map((group) => ({
    group,
    lines: lines.filter((l) => lineGroup(l) === group),
  })).filter((entry) => entry.lines.length > 0);
}

/**
 * Fronta průvodce „Doplnit nerozpoznané": nejdřív řádky, které appka nezná, pak odhady ke
 * kontrole. Jisté návrhy průvodce neukazuje — ty stačí hromadně potvrdit.
 */
export function guideQueue(lines: readonly (GroupSource & { id: string })[]): string[] {
  return [
    ...lines.filter((l) => lineGroup(l) === 'toResolve').map((l) => l.id),
    ...lines.filter((l) => lineGroup(l) === 'toCheck').map((l) => l.id),
  ];
}

/**
 * Další řádek průvodce po `currentId`: první z fronty za ním, který pořád potřebuje člověka
 * (mezitím ho mohlo vyřešit naučené označení). `null` = průvodce skončil.
 */
export function nextInGuide(
  queue: readonly string[],
  currentId: string | null,
  lines: readonly (GroupSource & { id: string })[],
): string | null {
  const needsHuman = new Set(
    lines
      .filter((l) => lineGroup(l) === 'toResolve' || lineGroup(l) === 'toCheck')
      .map((l) => l.id),
  );
  const start = currentId === null ? 0 : queue.indexOf(currentId) + 1;
  return queue.slice(Math.max(start, 0)).find((id) => needsHuman.has(id)) ?? null;
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
 * Zaškrtnutí po změně řádků (mutace, obnova ze serveru): řádek, který se NOVĚ stal jistým
 * návrhem (uživatel ho právě spároval, server přidal návrh, odhad nahradilo naučené označení),
 * se zaškrtne; řádek, který potvrditelný být přestal, se odškrtne. Co si uživatel u beze změny
 * potvrditelných řádků odškrtl, zůstane odškrtnuté.
 *
 * Odhad se sám nezaškrtne nikdy. Zaškrtnutí, které mu dal uživatel, vydrží jen do té doby,
 * dokud jde o tentýž odhad — když server po změně obchodu navrhne jiné zboží, uživatel ho
 * ještě neviděl.
 */
export function nextSelection(
  selected: ReadonlySet<string>,
  previousLines: readonly ReceiptLine[],
  nextLines: readonly ReceiptLine[],
): Set<string> {
  const previousById = new Map(previousLines.map((line) => [line.id, line]));
  const result = new Set<string>();
  for (const line of nextLines) {
    if (!isLineConfirmable(line)) continue;
    const previous = previousById.get(line.id);
    if (isLineGuess(line)) {
      const sameGuess =
        previous !== undefined &&
        isLineGuess(previous) &&
        previous.product?.id === line.product?.id;
      if (sameGuess && selected.has(line.id)) result.add(line.id);
      continue;
    }
    const wasPreselectable = previous !== undefined && isLinePreselectable(previous);
    if (!wasPreselectable || selected.has(line.id)) result.add(line.id);
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
