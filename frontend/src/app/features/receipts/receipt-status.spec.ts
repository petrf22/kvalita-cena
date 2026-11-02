import { describe, expect, it } from 'vitest';
import type { ReceiptDetail, ReceiptLine } from '../../models/catalog';
import {
  RECEIPT_CONSENT_VERSION,
  confirmBlocker,
  consentOutdated,
  defaultSelectedLineIds,
  isLineConfirmable,
  isLineGuess,
  isLineLocked,
  lineGtin,
  lineLabel,
  lineRawLabel,
  lineSummary,
  matchSourceKey,
  nextSelection,
  receiptStatusColor,
  receiptStatusKey,
  receiptStoreName,
  samePurchaseTime,
  skipReasonKey,
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

  it('předem zaškrtnuté jsou právě návrhy', () => {
    const lines = [line('1', { status: 'SUGGESTED' }), line('2'), line('3', { status: 'IGNORED' })];
    expect([...defaultSelectedLineIds(lines)]).toEqual(['1']);
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
