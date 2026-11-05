import { describe, expect, it } from 'vitest';
import type { ConfirmReceiptLinesResult, ReceiptDetail, ReceiptLine } from '../../models/catalog';
import {
  RECEIPT_CONSENT_VERSION,
  confirmBlocker,
  consentOutdated,
  defaultSelectedLineIds,
  groupedLines,
  guideQueue,
  isLineConfirmable,
  isLineNonProduct,
  isLineOffSuggestion,
  isLineGuess,
  isLineLocked,
  isLineReceiptItemCandidate,
  isLineSelectable,
  isLineToVerify,
  lineGtin,
  lineLabel,
  lineRawLabel,
  lineSummary,
  matchSourceKey,
  nextInGuide,
  nextSelection,
  publishSummary,
  receiptStatusColor,
  receiptStatusKey,
  receiptStoreName,
  samePurchaseTime,
  selectLabelKey,
  skipReasonKey,
  sumCheck,
  unselectedGuessCount,
} from './receipt-status';

function line(id: string, overrides: Partial<ReceiptLine> = {}): ReceiptLine {
  return {
    id,
    lineNo: Number(id),
    labelRaw: `RADEK ${id}`,
    labelDisplay: null,
    internalCode: null,
    quantity: 1,
    quantityUnit: 'PCS',
    unitPrice: 10,
    lineTotal: 10,
    discountAmount: null,
    priceKind: 'REGULAR',
    status: 'UNMATCHED',
    matchSource: null,
    product: null,
    observationId: null,
    confirmed: false,
    skipReason: null,
    nonProduct: false,
    suspicious: false,
    ...overrides,
  } as ReceiptLine;
}

describe('stav účtenky', () => {
  it('dokud stroj nedoběhl, ukazuje zpracování, potom párování', () => {
    expect(receiptStatusKey({ processingStatus: 'QUEUED', reviewStatus: 'PENDING' })).toBe(
      'enum.receiptProcessingStatus.QUEUED',
    );
    expect(receiptStatusKey({ processingStatus: 'PROCESSED', reviewStatus: 'PENDING' })).toBe(
      'enum.receiptReviewStatus.PENDING',
    );
    expect(receiptStatusKey({ processingStatus: 'UNREADABLE', reviewStatus: 'PENDING' })).toBe(
      'enum.receiptProcessingStatus.UNREADABLE',
    );
  });

  it('barvy jsou stavové předvolby nz-tag', () => {
    expect(receiptStatusColor({ processingStatus: 'PROCESSING', reviewStatus: 'PENDING' })).toBe(
      'processing',
    );
    expect(receiptStatusColor({ processingStatus: 'PROCESSED', reviewStatus: 'DONE' })).toBe(
      'success',
    );
    expect(receiptStatusColor({ processingStatus: 'FAILED', reviewStatus: 'PENDING' })).toBe(
      'error',
    );
  });

  it('název obchodu: potvrzený obchod, jinak text z hlavičky, prázdný text není název', () => {
    const store = { name: 'Billa' } as ReceiptDetail['store'];
    expect(receiptStoreName({ store, storeNameRaw: 'BILLA' })).toBe('Billa');
    expect(receiptStoreName({ store: null, storeNameRaw: ' albert ' })).toBe('albert');
    expect(receiptStoreName({ store: null, storeNameRaw: '  ' })).toBeNull();
  });
});

describe('výběr řádků k potvrzení', () => {
  it('potvrdit jde jen návrh, ze kterého ještě nevznikla cena', () => {
    expect(isLineConfirmable(line('1', { status: 'SUGGESTED' }))).toBe(true);
    expect(
      isLineConfirmable(line('1', { status: 'SUGGESTED', observationId: '9', confirmed: true })),
    ).toBe(false);
    expect(isLineConfirmable(line('1', { status: 'MATCHED' }))).toBe(false);
    expect(isLineConfirmable(line('1', { status: 'UNMATCHED' }))).toBe(false);
  });

  it('po pseudonymizaci zůstane potvrzený řádek zamčený i bez observationId', () => {
    const pseudonymized = line('1', { status: 'MATCHED', observationId: null, confirmed: true });
    expect(isLineLocked(pseudonymized)).toBe(true);
    expect(isLineConfirmable(pseudonymized)).toBe(false);
  });

  it('předem zaškrtnuté jsou návrhy i nespárované řádky, ignorované ne', () => {
    const lines = [line('1', { status: 'SUGGESTED' }), line('2'), line('3', { status: 'IGNORED' })];
    expect([...defaultSelectedLineIds(lines)]).toEqual(['1', '2']);
  });

  it('nově spárovaný řádek se zaškrtne, odškrtnutý návrh zůstane odškrtnutý', () => {
    const before = [line('1', { status: 'SUGGESTED' }), line('2')];
    const after = [line('1', { status: 'SUGGESTED' }), line('2', { status: 'SUGGESTED' })];
    expect([...nextSelection(new Set(), before, after)]).toEqual(['2']);
  });

  it('řádek, který přestal být návrhem, se odškrtne', () => {
    const before = [line('1', { status: 'SUGGESTED' })];
    const after = [line('1', { status: 'MATCHED', observationId: '5', confirmed: true })];
    expect([...nextSelection(new Set(['1']), before, after)]).toEqual([]);
  });
});

describe('odhad zboží podle podobnosti názvu (FUZZY)', () => {
  const product = (id: string) => ({ id }) as ReceiptLine['product'];
  const guess = (id: string, productId = 'p1') =>
    line(id, { status: 'SUGGESTED', matchSource: 'FUZZY', product: product(productId) });

  it('odhad jde potvrdit, ale je označený jako odhad; potvrzený už odhad není', () => {
    expect(isLineConfirmable(guess('1'))).toBe(true);
    expect(isLineGuess(guess('1'))).toBe(true);
    expect(matchSourceKey(guess('1'))).toBe('enum.receiptMatchSource.FUZZY');
    expect(isLineGuess(line('1', { status: 'SUGGESTED', matchSource: 'LABEL' }))).toBe(false);
    expect(
      isLineGuess(
        line('1', { status: 'MATCHED', matchSource: 'FUZZY', observationId: '3', confirmed: true }),
      ),
    ).toBe(false);
  });

  it('předem se nezaškrtne, jisté návrhy ano', () => {
    const lines = [
      line('1', { status: 'SUGGESTED', matchSource: 'CODE' }),
      guess('2'),
      line('3', { status: 'SUGGESTED', matchSource: 'LABEL' }),
    ];
    expect([...defaultSelectedLineIds(lines)]).toEqual(['1', '3']);
  });

  it('nový odhad od serveru se po obnově nezaškrtne', () => {
    const before = [line('1')];
    const after = [guess('1')];
    expect([...nextSelection(new Set(), before, after)]).toEqual([]);
  });

  it('odhad, který si uživatel zaškrtl sám, zůstane zaškrtnutý', () => {
    const lines = [guess('1')];
    expect([...nextSelection(new Set(['1']), lines, lines)]).toEqual(['1']);
  });

  it('jiný odhad po změně obchodu zaškrtnutí ztratí — nové zboží uživatel neviděl', () => {
    const before = [guess('1', 'p1')];
    const after = [guess('1', 'p2')];
    expect([...nextSelection(new Set(['1']), before, after)]).toEqual([]);
  });

  it('jistý návrh, ze kterého se stal odhad, zaškrtnutí ztratí', () => {
    const before = [
      line('1', { status: 'SUGGESTED', matchSource: 'LABEL', product: product('p1') }),
    ];
    const after = [guess('1', 'p2')];
    expect([...nextSelection(new Set(['1']), before, after)]).toEqual([]);
  });

  it('když uživatel u odhadu vybere zboží ručně, nebo ho přebije naučené označení, zaškrtne se', () => {
    const before = [guess('1'), guess('2')];
    const after = [
      line('1', { status: 'SUGGESTED', matchSource: 'USER', product: product('p1') }),
      line('2', { status: 'SUGGESTED', matchSource: 'LABEL', product: product('p1') }),
    ];
    expect([...nextSelection(new Set(), before, after)]).toEqual(['1', '2']);
  });
});

describe('název řádku', () => {
  it('ukazuje čitelný název a syrový tisk jen tehdy, když se liší', () => {
    const pretty = line('1', { labelRaw: 'ROHLÍK43GR', labelDisplay: 'Rohlík 43g' });
    expect(lineLabel(pretty)).toBe('Rohlík 43g');
    expect(lineRawLabel(pretty)).toBe('ROHLÍK43GR');
  });

  it('starší účtenka bez čitelného názvu ukáže syrový tisk jen jednou', () => {
    const old = line('1', { labelRaw: 'ROHLÍK43GR', labelDisplay: null });
    expect(lineLabel(old)).toBe('ROHLÍK43GR');
    expect(lineRawLabel(old)).toBeNull();
  });

  it('prázdný čitelný název se nebere, shodný se syrovým se neopakuje', () => {
    expect(lineLabel(line('1', { labelRaw: 'MLEKO', labelDisplay: '  ' }))).toBe('MLEKO');
    expect(lineRawLabel(line('1', { labelRaw: 'Mléko', labelDisplay: 'Mléko' }))).toBeNull();
  });
});

describe('proč potvrzení nejde', () => {
  const ready = {
    processingStatus: 'PROCESSED',
    store: { id: '1' },
    purchasedAt: '2026-09-01T10:00:00Z',
  } as Pick<ReceiptDetail, 'processingStatus' | 'store' | 'purchasedAt'>;

  it('chybí obchod, datum nebo výběr — v tomhle pořadí', () => {
    expect(confirmBlocker({ ...ready, processingStatus: 'QUEUED' }, 1)).toBe('notProcessed');
    expect(confirmBlocker({ ...ready, store: null }, 1)).toBe('storeMissing');
    expect(confirmBlocker({ ...ready, purchasedAt: null }, 1)).toBe('purchaseTimeMissing');
    expect(confirmBlocker(ready, 0)).toBe('nothing');
    expect(confirmBlocker(ready, 2)).toBeNull();
  });
});

describe('ostatní', () => {
  it('duplicita má vlastní text, jiné důvody jdou přes kontrakt chyb', () => {
    expect(skipReasonKey('DUPLICATE_SAME_DAY')).toBe('receipts.skip.duplicateSameDay');
    expect(skipReasonKey('RECEIPT_LINE_PRICE_MISSING')).toBe('errors.RECEIPT_LINE_PRICE_MISSING');
  });

  it('souhrn řádků', () => {
    const lines = [
      line('1'),
      line('2', { status: 'SUGGESTED' }),
      line('3', { status: 'MATCHED', observationId: '7', confirmed: true }),
      line('4', { status: 'IGNORED' }),
    ];
    expect(lineSummary(lines)).toEqual({ total: 4, open: 1, ready: 1, recorded: 1 });
  });

  it('čas nákupu se porovnává jako okamžik, ne jako text', () => {
    expect(samePurchaseTime(new Date('2026-08-30T09:10:00Z'), '2026-08-30T09:10Z')).toBe(true);
    expect(samePurchaseTime(null, null)).toBe(true);
    expect(samePurchaseTime(new Date(), null)).toBe(false);
  });
});

describe('verze souhlasu', () => {
  it('odeslání blokuje jen jiné znění na serveru, než jaké web ukazuje', () => {
    expect(consentOutdated(null)).toBe(false);
    expect(consentOutdated(RECEIPT_CONSENT_VERSION)).toBe(false);
    expect(consentOutdated(RECEIPT_CONSENT_VERSION + 1)).toBe(true);
  });
});

describe('EAN řádku', () => {
  it('vrátí globální EAN z účtenky', () => {
    expect(lineGtin(line('1', { internalCode: '8594001740659' }))).toBe('8594001740659');
    expect(lineGtin(line('1', { internalCode: ' 8594001740659 ' }))).toBe('8594001740659');
  });

  it('vnitroobchodní kód, etiketu váhového zboží ani špatnou kontrolní číslici za EAN nevydává', () => {
    expect(lineGtin(line('1', { internalCode: null }))).toBeNull();
    expect(lineGtin(line('1', { internalCode: '123456' }))).toBeNull();
    expect(lineGtin(line('1', { internalCode: '2012345678903' }))).toBeNull();
    expect(lineGtin(line('1', { internalCode: '5997321796297' }))).toBeNull();
  });

  it('párování podle kódu s EANem popíše jako „podle EAN"', () => {
    expect(matchSourceKey(line('1', { matchSource: 'CODE', internalCode: '8594001740659' }))).toBe(
      'receipts.line.matchedByEan',
    );
  });

  it('ostatní zdroje párování nechá u obecného popisku', () => {
    expect(matchSourceKey(line('1', { matchSource: 'CODE', internalCode: '123456' }))).toBe(
      'enum.receiptMatchSource.CODE',
    );
    expect(matchSourceKey(line('1', { matchSource: 'LABEL', internalCode: '8594001740659' }))).toBe(
      'enum.receiptMatchSource.LABEL',
    );
    expect(matchSourceKey(line('1', { matchSource: null }))).toBeNull();
  });
});

describe('skupiny řádků a průvodce', () => {
  const lines = [
    line('1', { status: 'SUGGESTED', matchSource: 'CODE' }),
    line('2', { status: 'SUGGESTED', matchSource: 'FUZZY' }),
    line('3', { status: 'UNMATCHED' }),
    line('4', { status: 'MATCHED', matchSource: 'LABEL', confirmed: true }),
    line('5', { status: 'IGNORED' }),
    line('6', { status: 'SUGGESTED', matchSource: 'OFF' }),
    line('7', { status: 'UNMATCHED' }),
  ];

  it('skupiny jdou od toho, co potřebuje člověka', () => {
    expect(groupedLines(lines).map((g) => [g.group, g.lines.map((l) => l.id)])).toEqual([
      ['toResolve', ['3', '7']],
      ['toCheck', ['2']],
      ['ready', ['1', '6']],
      ['done', ['4']],
      ['ignored', ['5']],
    ]);
  });

  it('návrh z Open Food Facts je předem zaškrtnutý jako shoda EAN, odhad ne', () => {
    expect(isLineOffSuggestion(lines[5])).toBe(true);
    // 3 a 7 jsou nespárované — zveřejní se jako zboží z účtenky.
    expect(defaultSelectedLineIds(lines)).toEqual(new Set(['1', '3', '6', '7']));
  });

  it('průvodce projde nespárované, pak odhady, a přeskočí vyřešené', () => {
    const queue = guideQueue(lines);
    expect(queue).toEqual(['3', '7', '2']);
    const resolved = lines.map((l) =>
      l.id === '7' ? { ...l, status: 'SUGGESTED' as const, matchSource: 'LABEL' as const } : l,
    );
    expect(nextInGuide(queue, '3', resolved)).toBe('2');
    expect(nextInGuide(queue, null, resolved)).toBe('3');
    expect(nextInGuide(queue, '2', resolved)).toBeNull();
  });
});

describe('zveřejnění nespárovaných řádků jako zboží z účtenky', () => {
  it('nespárovaný řádek jde zveřejnit, záloha ani ignorovaný ne', () => {
    expect(isLineSelectable(line('1'))).toBe(true);
    expect(isLineReceiptItemCandidate(line('1'))).toBe(true);
    expect(isLineSelectable(line('1', { nonProduct: true }))).toBe(false);
    expect(isLineNonProduct(line('1', { nonProduct: true }))).toBe(true);
    expect(isLineSelectable(line('1', { status: 'IGNORED' }))).toBe(false);
    expect(isLineSelectable(line('1', { status: 'MATCHED', confirmed: true }))).toBe(false);
  });

  it('zálohu spárovanou se zbožím zveřejnit jde, ale předem se nezaškrtne', () => {
    const deposit = line('1', { status: 'SUGGESTED', matchSource: 'USER', nonProduct: true });
    expect(isLineSelectable(deposit)).toBe(true);
    expect(isLineNonProduct(deposit)).toBe(false);
    expect([...defaultSelectedLineIds([deposit])]).toEqual([]);
  });

  it('předvýběr: všechno kromě zálohy, odhadu, ignorovaných a už zveřejněných', () => {
    const lines = [
      line('1'),
      line('2', { nonProduct: true }),
      line('3', { status: 'SUGGESTED', matchSource: 'FUZZY' }),
      line('4', { status: 'IGNORED' }),
      line('5', { status: 'MATCHED', matchSource: 'RECEIPT_ITEM', confirmed: true }),
      line('6', { status: 'SUGGESTED', matchSource: 'CODE', suspicious: true }),
    ];
    expect([...defaultSelectedLineIds(lines)]).toEqual(['1', '6']);
  });

  it('řádek, který server minule přeskočil, se předem nezaškrtne', () => {
    const lines = [
      line('1', { skipReason: 'RECEIPT_LINE_PRICE_MISSING' }),
      line('2', { skipReason: 'RECEIPT_ITEM_LIMIT' }),
      line('3'),
    ];
    expect([...defaultSelectedLineIds(lines)]).toEqual(['3']);
  });

  it('odškrtnutý nespárovaný řádek zůstane po obnově odškrtnutý', () => {
    const lines = [line('1'), line('2')];
    expect([...nextSelection(new Set(['2']), lines, lines)]).toEqual(['2']);
  });

  it('nespárovaný řádek, který uživatel spároval, se zaškrtne, i když byl odškrtnutý', () => {
    const product = { id: 'p1' } as ReceiptLine['product'];
    const before = [line('1')];
    const after = [line('1', { status: 'SUGGESTED', matchSource: 'USER', product })];
    expect([...nextSelection(new Set(), before, after)]).toEqual(['1']);
  });

  it('odhad se zrušeným párováním se zaškrtne jako zboží z účtenky', () => {
    const product = { id: 'p1' } as ReceiptLine['product'];
    const before = [line('1', { status: 'SUGGESTED', matchSource: 'FUZZY', product })];
    const after = [line('1')];
    expect([...nextSelection(new Set(), before, after)]).toEqual(['1']);
  });

  it('řádek vrácený z ignorovaných se zaškrtne', () => {
    const before = [line('1', { status: 'IGNORED' })];
    const after = [line('1')];
    expect([...nextSelection(new Set(), before, after)]).toEqual(['1']);
  });

  it('zálohu, kterou si uživatel zaškrtl sám, nechá zaškrtnutou, sama ji nezaškrtne', () => {
    const deposit = [line('1', { status: 'SUGGESTED', matchSource: 'USER', nonProduct: true })];
    const before = [line('1', { nonProduct: true })];
    expect([...nextSelection(new Set(), before, deposit)]).toEqual([]);
    expect([...nextSelection(new Set(['1']), deposit, deposit)]).toEqual(['1']);
  });

  it('popisek zaškrtávátka řekne, že se řádek zveřejní jako zboží z účtenky', () => {
    expect(selectLabelKey(line('1'))).toBe('receipts.line.selectReceiptItemLabel');
    expect(selectLabelKey(line('1', { status: 'SUGGESTED', matchSource: 'FUZZY' }))).toBe(
      'receipts.line.selectGuessLabel',
    );
    expect(selectLabelKey(line('1', { status: 'SUGGESTED', matchSource: 'CODE' }))).toBe(
      'receipts.line.selectLabel',
    );
  });

  it('spočítá odhady, které se bez zaškrtnutí nezveřejní', () => {
    const lines = [
      line('1', { status: 'SUGGESTED', matchSource: 'FUZZY' }),
      line('2', { status: 'SUGGESTED', matchSource: 'FUZZY' }),
      line('3'),
    ];
    expect(unselectedGuessCount(lines, new Set(['2', '3']))).toBe(1);
  });
});

describe('kontrolní součet účtenky', () => {
  const receipt = (overrides: Partial<ReceiptDetail> = {}) =>
    ({
      sumStatus: 'MATCH',
      totalAmount: 245.9,
      computedTotal: 245.9,
      lines: [line('1')],
      ...overrides,
    }) as ReceiptDetail;

  it('když součet sedí, neupozorňuje na nic', () => {
    expect(sumCheck(receipt())).toBeNull();
  });

  it('nesouhlas ukáže obě částky a rozdíl bez znaménka a bez plovoucí chyby', () => {
    expect(sumCheck(receipt({ sumStatus: 'MISMATCH', computedTotal: 232.8 }))).toEqual({
      kind: 'mismatch',
      computed: 232.8,
      total: 245.9,
      difference: 13.1,
    });
    // Položky dávají víc, než je vytištěné „Celkem" — rozdíl je pořád kladný.
    expect(sumCheck(receipt({ sumStatus: 'MISMATCH', computedTotal: 250.1 }))).toMatchObject({
      kind: 'mismatch',
      difference: 4.2,
    });
  });

  it('nesouhlas bez jedné z částek a neznámý součet jsou obecná výzva ke kontrole', () => {
    expect(sumCheck(receipt({ sumStatus: 'MISMATCH', totalAmount: null }))).toEqual({
      kind: 'unknown',
    });
    expect(sumCheck(receipt({ sumStatus: 'UNKNOWN', totalAmount: null }))).toEqual({
      kind: 'unknown',
    });
  });

  it('u účtenky, kde už není co zveřejnit, mlčí', () => {
    const done = [
      line('1', { status: 'MATCHED', confirmed: true }),
      line('2', { status: 'IGNORED' }),
      line('3', { nonProduct: true }),
    ];
    expect(sumCheck(receipt({ sumStatus: 'UNKNOWN', lines: done }))).toBeNull();
  });

  it('podezřelý řádek se zvýrazní jen při nesouhlasu a jen dokud není zveřejněný', () => {
    const suspicious = line('1', { suspicious: true });
    const check = sumCheck(receipt({ sumStatus: 'UNKNOWN' }));
    expect(isLineToVerify(suspicious, check)).toBe(true);
    expect(isLineToVerify(suspicious, null)).toBe(false);
    expect(isLineToVerify(line('2'), check)).toBe(false);
    expect(
      isLineToVerify(line('3', { suspicious: true, status: 'MATCHED', confirmed: true }), check),
    ).toBe(false);
  });
});

describe('souhrn po zveřejnění', () => {
  const result = (
    lines: ReceiptLine[],
    confirmed: number,
    skipped: ReceiptLine[] = [],
  ): ConfirmReceiptLinesResult =>
    ({ receipt: { lines }, confirmed, skipped }) as unknown as ConfirmReceiptLinesResult;

  it('jako zboží z účtenky počítá jen řádky z tohoto zveřejnění', () => {
    const lines = [
      line('1', { status: 'MATCHED', matchSource: 'RECEIPT_ITEM', confirmed: true }),
      line('2', { status: 'MATCHED', matchSource: 'RECEIPT_ITEM', confirmed: true }),
      line('3', { status: 'MATCHED', matchSource: 'LABEL', confirmed: true }),
      line('4', { status: 'MATCHED', matchSource: 'CODE', confirmed: true }),
      line('5', {
        status: 'MATCHED',
        matchSource: 'LABEL',
        confirmed: true,
        product: { createdVia: 'RECEIPT' } as ReceiptLine['product'],
      }),
    ];
    // Řádek 1 se zveřejnil už dřív; řádky 3 a 5 mezitím spárovalo naučené označení, 5 na
    // existující zboží z účtenky — to se počítá, 3 míří na běžné zboží.
    expect(publishSummary(['2', '3', '4', '5'], result(lines, 4))).toEqual({
      published: 4,
      receiptItems: 2,
      skippedCount: 0,
      skipped: [],
    });
  });

  it('přeskočené řádky seskupí podle důvodu v pořadí, jak přišly', () => {
    const skipped = [
      line('1', { labelDisplay: 'Záloha lahev', skipReason: 'RECEIPT_LINE_NOT_PRODUCT' }),
      line('2', { labelDisplay: 'Rohlík', skipReason: 'DUPLICATE_SAME_DAY' }),
      line('3', { labelDisplay: 'Vratný obal', skipReason: 'RECEIPT_LINE_NOT_PRODUCT' }),
      line('4', { labelDisplay: 'Mléko', skipReason: 'RECEIPT_ITEM_LIMIT' }),
    ];
    expect(publishSummary(['1', '2', '3', '4'], result([], 0, skipped))).toEqual({
      published: 0,
      receiptItems: 0,
      skippedCount: 4,
      skipped: [
        { reasonKey: 'errors.RECEIPT_LINE_NOT_PRODUCT', labels: ['Záloha lahev', 'Vratný obal'] },
        { reasonKey: 'receipts.skip.duplicateSameDay', labels: ['Rohlík'] },
        { reasonKey: 'receipts.skip.itemLimit', labels: ['Mléko'] },
      ],
    });
  });

  it('strop zboží z účtenek má text bez čísla, chybějící důvod je obecné selhání', () => {
    expect(skipReasonKey('RECEIPT_ITEM_LIMIT')).toBe('receipts.skip.itemLimit');
    expect(skipReasonKey(null)).toBe('errors.RECEIPT_LINE_CONFIRM_FAILED');
  });
});
