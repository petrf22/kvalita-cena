import { isReceiptItem } from '../../shared/receipt-item-tag';
import type {
  ConfirmReceiptLinesResult,
  ReceiptDetail,
  ReceiptLine,
  ReceiptListItem,
} from '../../models/catalog';
import {
  ErrorCode,
  ReceiptLineStatus,
  ReceiptMatchSource,
  ReceiptProcessingStatus,
  ReceiptReviewStatus,
  ReceiptSumStatus,
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

/** Návrh zboží, ze kterého ještě nevznikla cena (skupina „Připraveno", průvodce, odhad). */
export function isLineConfirmable(line: Pick<ReceiptLine, 'status' | 'confirmed'>): boolean {
  return line.status === ReceiptLineStatus.Suggested && !isLineLocked(line);
}

type SelectSource = Pick<ReceiptLine, 'status' | 'confirmed' | 'nonProduct'>;

/**
 * Řádek jde zaškrtnout ke zveřejnění: cena z něj ještě nevznikla a server ji umí zapsat —
 * návrh zboží, nebo nespárovaný řádek, který se zveřejní jako „zboží z účtenky"
 * (`confirmReceiptLines(publishUnmatched: true)`). Záloha a vratný obal (`nonProduct`) bez
 * zboží ne: server by ho jen přeskočil (`RECEIPT_LINE_NOT_PRODUCT`). Kdo ho přesto spáruje se
 * zbožím, z návrhu ho zveřejnit může. Ignorovaný řádek je rozhodnutí uživatele „není zboží".
 */
export function isLineSelectable(line: SelectSource): boolean {
  if (isLineLocked(line)) return false;
  if (line.status === ReceiptLineStatus.Suggested) return true;
  return line.status === ReceiptLineStatus.Unmatched && !line.nonProduct;
}

/** Nespárovaný řádek, který se zaškrtnutý zveřejní jako „zboží z účtenky" (bez ověřených náležitostí). */
export function isLineReceiptItemCandidate(line: SelectSource): boolean {
  return line.status === ReceiptLineStatus.Unmatched && isLineSelectable(line);
}

/** Nespárovaný řádek, který podle textu není zboží (záloha, vratný obal) — nezveřejní se. */
export function isLineNonProduct(line: SelectSource): boolean {
  return line.status === ReceiptLineStatus.Unmatched && line.nonProduct && !isLineLocked(line);
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

/**
 * Řádek appka smí zaškrtnout sama: všechno, co jde zveřejnit, kromě odhadu, zálohy či obalu
 * a řádku, který server při minulém zveřejnění přeskočil (`skipReason` — chybí cena, strop
 * zboží z účtenek): zaškrtnutý by se jen znovu přeskočil, takže ho zaškrtne až člověk.
 *
 * Proč odhad (`FUZZY`) ne, i když se teď zveřejňuje všechno: nespárovaný řádek skončí na
 * izolovaném „zboží z účtenky", které jde později dopárovat. Potvrzený odhad naopak zapíše cenu
 * ke SKUTEČNÉMU zboží, naučí se jako označení pro celý řetězec a zboží dostane EAN z řádku —
 * omyl z hromadného zaškrtnutí by se šířil na další účtenky (docs/ai.md). Odhad proto zaškrtává
 * jen člověk; kdo ho zamítne („Zrušit párování"), z řádku se stane nespárovaný a zaškrtne se
 * jako zboží z účtenky (`nextSelection`).
 */
function isLinePreselectable(
  line: SelectSource & Pick<ReceiptLine, 'matchSource' | 'skipReason'>,
): boolean {
  return isLineSelectable(line) && !isLineGuess(line) && !line.nonProduct && !line.skipReason;
}

/** Popisek zaškrtávátka řádku pro čtečky — klíč do bundlu `receipts`, parametr `label`. */
export function selectLabelKey(
  line: SelectSource & Pick<ReceiptLine, 'matchSource'>,
):
  | 'receipts.line.selectGuessLabel'
  | 'receipts.line.selectReceiptItemLabel'
  | 'receipts.line.selectLabel' {
  if (isLineGuess(line)) return 'receipts.line.selectGuessLabel';
  if (isLineReceiptItemCandidate(line)) return 'receipts.line.selectReceiptItemLabel';
  return 'receipts.line.selectLabel';
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
 * Výchozí zaškrtnutí — všechno, co jde zveřejnit (návrhy i nespárované řádky jako zboží
 * z účtenky), kromě odhadu (`isLineGuess`), zálohy či obalu (`nonProduct`) a řádku, který
 * server minule přeskočil (`skipReason`, viz `isLinePreselectable`). Platí i pro
 * nesouhlasící kontrolní součet: uživatel odškrtne, co nechce, appka mu jen ukáže podezřelé
 * řádky (`sumCheck`).
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
 * Důvody přeskočení s vlastním textem. `RECEIPT_ITEM_LIMIT` je v kontraktu chyb s parametrem
 * (strop otevřených položek), který `skipReason` nenese — text z `errors.*` by ukázal prázdné
 * místo, proto má verzi bez čísla.
 */
const SKIP_REASON_KEYS: Record<string, string> = {
  [SKIP_DUPLICATE_SAME_DAY]: 'receipts.skip.duplicateSameDay',
  [ErrorCode.ReceiptItemLimit]: 'receipts.skip.itemLimit',
};

/**
 * Překladový klíč důvodu přeskočení: duplicita a strop zboží z účtenek mají vlastní text,
 * ostatní důvody jsou kódy `ErrorCode` a berou se z kontraktu chyb (`errors.*`,
 * docs/lokalizace.md). Chybějící důvod je obecné selhání řádku.
 */
export function skipReasonKey(reason: string | null | undefined): string {
  if (!reason) return 'errors.RECEIPT_LINE_CONFIRM_FAILED';
  return SKIP_REASON_KEYS[reason] ?? `errors.${reason}`;
}

/** Přeskočené řádky jednoho důvodu — souhrn po zveřejnění je seskupuje, ať se důvod neopakuje. */
export interface SkippedGroup {
  reasonKey: string;
  labels: string[];
}

/** Souhrn po „Zveřejnit vybrané": kolik cen vzniklo, kolik z toho na zboží z účtenky, co a proč se přeskočilo. */
export interface PublishSummary {
  published: number;
  receiptItems: number;
  skippedCount: number;
  skipped: SkippedGroup[];
}

/**
 * `requestedIds` jsou řádky, které klient poslal — „jako zboží z účtenky" se počítá jen z nich,
 * ne ze dřív zveřejněných řádků téže účtenky. Počítá se každý potvrzený řádek, jehož cena je na
 * zboží z účtenky: nově založeném (`matchSource: RECEIPT_ITEM`) i existujícím, na které řádek
 * mezitím spárovalo naučené označení (`LABEL` + `product.createdVia: RECEIPT`) — stejně jako mobil.
 */
export function publishSummary(
  requestedIds: readonly string[],
  result: Pick<ConfirmReceiptLinesResult, 'confirmed' | 'skipped' | 'receipt'>,
): PublishSummary {
  const requested = new Set(requestedIds);
  const receiptItems = result.receipt.lines.filter(
    (line) =>
      requested.has(line.id) &&
      line.confirmed &&
      (line.matchSource === ReceiptMatchSource.ReceiptItem || isReceiptItem(line.product)),
  ).length;
  const groups = new Map<string, string[]>();
  for (const line of result.skipped) {
    const key = skipReasonKey(line.skipReason);
    groups.set(key, [...(groups.get(key) ?? []), lineLabel(line)]);
  }
  return {
    published: result.confirmed,
    receiptItems: Math.min(receiptItems, result.confirmed),
    skippedCount: result.skipped.length,
    skipped: [...groups].map(([reasonKey, labels]) => ({ reasonKey, labels })),
  };
}

/** Upozornění nad seznamem řádků podle kontrolního součtu workeru (`Receipt.sumStatus`). */
export type SumCheck =
  { kind: 'mismatch'; computed: number; total: number; difference: number } | { kind: 'unknown' };

/**
 * `MATCH` = nic. `MISMATCH` = obě částky a rozdíl (vytištěné „Celkem" − součet položek, v absolutní
 * hodnotě). `UNKNOWN` — nebo nesouhlas bez jedné z částek — jen obecná výzva ke kontrole. Jen
 * dokud je co zveřejnit: u hotové účtenky by upozornění jen strašilo. Předvýběr řádků se podle
 * součtu NEmění (`defaultSelectedLineIds`), mění se jen to, co appka ukáže.
 */
export function sumCheck(
  receipt: Pick<ReceiptDetail, 'sumStatus' | 'totalAmount' | 'computedTotal' | 'lines'>,
): SumCheck | null {
  if (receipt.sumStatus === ReceiptSumStatus.Match) return null;
  if (!receipt.lines.some(isLineSelectable)) return null;
  const { totalAmount: total, computedTotal: computed } = receipt;
  if (receipt.sumStatus === ReceiptSumStatus.Mismatch && total != null && computed != null) {
    const difference = Math.round(Math.abs(total - computed) * 100) / 100;
    return { kind: 'mismatch', computed, total, difference };
  }
  return { kind: 'unknown' };
}

/** Řádek ke kontrole: podezřelý (`suspicious`), dosud nezveřejněný, a součet nesouhlasí nebo ho nešlo ověřit. */
export function isLineToVerify(
  line: Pick<ReceiptLine, 'suspicious' | 'confirmed'>,
  check: SumCheck | null,
): boolean {
  return check !== null && line.suspicious && !isLineLocked(line);
}

/** Odhady, které uživatel nezaškrtl — bez jeho rozhodnutí se nezveřejní (`isLinePreselectable`). */
export function unselectedGuessCount(
  lines: readonly ReceiptLine[],
  selected: ReadonlySet<string>,
): number {
  return lines.filter((line) => isLineGuess(line) && !selected.has(line.id)).length;
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
 * S čím server řádek zveřejní: stav, zdroj párování a zboží. Shoda znamená „uživatel o tomhle
 * řádku už rozhodl" — změna (spárování, zrušení párování, nový návrh po změně obchodu) je pro
 * něj nová věc a výchozí zaškrtnutí platí znovu.
 */
function samePublishTarget(
  a: Pick<ReceiptLine, 'status' | 'matchSource' | 'product'>,
  b: Pick<ReceiptLine, 'status' | 'matchSource' | 'product'>,
): boolean {
  return (
    a.status === b.status &&
    a.matchSource === b.matchSource &&
    (a.product?.id ?? null) === (b.product?.id ?? null)
  );
}

/**
 * Zaškrtnutí po změně řádků (mutace, obnova ze serveru): řádek, se kterým se NOVĚ zveřejní
 * něco jiného (uživatel ho právě spároval nebo párování zrušil, server přidal návrh, odhad
 * nahradilo naučené označení), dostane výchozí zaškrtnutí; řádek, který zveřejnit nejde, se
 * odškrtne. Co si uživatel u beze změny zveřejnitelných řádků odškrtl, zůstane odškrtnuté.
 *
 * Odhad ani záloha se sami nezaškrtnou nikdy. Zaškrtnutí, které jim dal uživatel, vydrží jen do
 * té doby, dokud jde o totéž — když server po změně obchodu navrhne jiné zboží, uživatel ho
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
    if (!isLineSelectable(line)) continue;
    const previous = previousById.get(line.id);
    const unchanged = previous !== undefined && samePublishTarget(previous, line);
    if (!isLinePreselectable(line)) {
      if (unchanged && selected.has(line.id)) result.add(line.id);
      continue;
    }
    if (!unchanged || selected.has(line.id)) result.add(line.id);
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
