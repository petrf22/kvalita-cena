import { describe, expect, it } from 'vitest';
import { PriceKind } from '../models/catalog';
import {
  arePriceRowsValid,
  availablePriceKinds,
  duplicatePriceKind,
  isPriceRowValid,
  newPriceRow,
  toObservationPriceInputs,
  type PriceRow,
} from './price-rows';

function row(overrides: Partial<PriceRow> = {}): PriceRow {
  return {
    key: 1,
    priceKind: PriceKind.Regular,
    priceAmount: null,
    multibuyQty: null,
    multibuyTotal: null,
    promoValidFrom: null,
    promoValidTo: null,
    ...overrides,
  };
}

describe('isPriceRowValid', () => {
  it('requires priceAmount for non-MULTIBUY kinds', () => {
    expect(isPriceRowValid(row({ priceAmount: 29.9 }))).toBe(true);
    expect(isPriceRowValid(row({ priceAmount: null }))).toBe(false);
  });

  it('requires multibuyQty >= 2 and multibuyTotal for MULTIBUY', () => {
    expect(
      isPriceRowValid(row({ priceKind: PriceKind.Multibuy, multibuyQty: 3, multibuyTotal: 50 })),
    ).toBe(true);
    expect(
      isPriceRowValid(row({ priceKind: PriceKind.Multibuy, multibuyQty: 1, multibuyTotal: 50 })),
    ).toBe(false);
    expect(
      isPriceRowValid(row({ priceKind: PriceKind.Multibuy, multibuyQty: null, multibuyTotal: 50 })),
    ).toBe(false);
    expect(
      isPriceRowValid(row({ priceKind: PriceKind.Multibuy, multibuyQty: 3, multibuyTotal: null })),
    ).toBe(false);
  });
});

function isoDateOffset(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return date.toISOString().slice(0, 10);
}

describe('isPriceRowValid — promo validity', () => {
  it('allows PROMO without any validity dates', () => {
    expect(isPriceRowValid(row({ priceKind: PriceKind.Promo, priceAmount: 19.9 }))).toBe(true);
  });

  it('allows PROMO with a validity range in the past/near future', () => {
    expect(
      isPriceRowValid(
        row({
          priceKind: PriceKind.Promo,
          priceAmount: 19.9,
          promoValidFrom: isoDateOffset(-2),
          promoValidTo: isoDateOffset(5),
        }),
      ),
    ).toBe(true);
  });

  it('rejects validity dates on any kind other than PROMO', () => {
    expect(
      isPriceRowValid(
        row({ priceKind: PriceKind.Regular, priceAmount: 29.9, promoValidTo: isoDateOffset(5) }),
      ),
    ).toBe(false);
  });

  it('rejects promoValidFrom after promoValidTo', () => {
    expect(
      isPriceRowValid(
        row({
          priceKind: PriceKind.Promo,
          priceAmount: 19.9,
          promoValidFrom: isoDateOffset(0),
          promoValidTo: isoDateOffset(-1),
        }),
      ),
    ).toBe(false);
  });

  it('rejects promoValidFrom in the future', () => {
    expect(
      isPriceRowValid(
        row({ priceKind: PriceKind.Promo, priceAmount: 19.9, promoValidFrom: isoDateOffset(1) }),
      ),
    ).toBe(false);
  });
});

describe('duplicatePriceKind', () => {
  it('is null when every kind is unique', () => {
    expect(
      duplicatePriceKind([
        row({ key: 1, priceKind: PriceKind.Regular }),
        row({ key: 2, priceKind: PriceKind.ClubCard }),
      ]),
    ).toBeNull();
  });

  it('reports the kind that repeats, in the order it is seen the second time', () => {
    expect(
      duplicatePriceKind([
        row({ key: 1, priceKind: PriceKind.Regular }),
        row({ key: 2, priceKind: PriceKind.ClubCard }),
        row({ key: 3, priceKind: PriceKind.Regular }),
      ]),
    ).toBe(PriceKind.Regular);
  });
});

describe('arePriceRowsValid', () => {
  it('is false for an empty list', () => {
    expect(arePriceRowsValid([])).toBe(false);
  });

  it('is false when a duplicate kind is present, even if every row is otherwise valid', () => {
    expect(
      arePriceRowsValid([
        row({ key: 1, priceKind: PriceKind.Regular, priceAmount: 10 }),
        row({ key: 2, priceKind: PriceKind.Regular, priceAmount: 20 }),
      ]),
    ).toBe(false);
  });

  it('is false when any row is incomplete', () => {
    expect(
      arePriceRowsValid([
        row({ key: 1, priceKind: PriceKind.Regular, priceAmount: 10 }),
        row({ key: 2, priceKind: PriceKind.ClubCard, priceAmount: null }),
      ]),
    ).toBe(false);
  });

  it('is true for a valid multi-row batch', () => {
    expect(
      arePriceRowsValid([
        row({ key: 1, priceKind: PriceKind.Regular, priceAmount: 29.9 }),
        row({ key: 2, priceKind: PriceKind.ClubCard, priceAmount: 24.9 }),
        row({ key: 3, priceKind: PriceKind.Multibuy, multibuyQty: 3, multibuyTotal: 50 }),
      ]),
    ).toBe(true);
  });
});

describe('toObservationPriceInputs', () => {
  it('does not send multibuyQty/multibuyTotal for REGULAR', () => {
    const [input] = toObservationPriceInputs([
      row({ priceKind: PriceKind.Regular, priceAmount: 29.9 }),
    ]);
    expect(input).toEqual({ priceKind: PriceKind.Regular, priceAmount: 29.9 });
    expect(input).not.toHaveProperty('multibuyQty');
    expect(input).not.toHaveProperty('multibuyTotal');
  });

  it('does not send priceAmount for MULTIBUY', () => {
    const [input] = toObservationPriceInputs([
      row({ priceKind: PriceKind.Multibuy, multibuyQty: 3, multibuyTotal: 50 }),
    ]);
    expect(input).toEqual({
      priceKind: PriceKind.Multibuy,
      multibuyQty: 3,
      multibuyTotal: 50,
    });
    expect(input).not.toHaveProperty('priceAmount');
  });

  it('sends promoValidFrom/promoValidTo only for PROMO', () => {
    const [promoInput] = toObservationPriceInputs([
      row({
        priceKind: PriceKind.Promo,
        priceAmount: 19.9,
        promoValidFrom: '2026-08-01',
        promoValidTo: '2026-08-31',
      }),
    ]);
    expect(promoInput).toEqual({
      priceKind: PriceKind.Promo,
      priceAmount: 19.9,
      promoValidFrom: '2026-08-01',
      promoValidTo: '2026-08-31',
    });

    const [regularInput] = toObservationPriceInputs([
      row({ priceKind: PriceKind.Regular, priceAmount: 29.9 }),
    ]);
    expect(regularInput).not.toHaveProperty('promoValidFrom');
    expect(regularInput).not.toHaveProperty('promoValidTo');
  });
});

describe('availablePriceKinds', () => {
  it('excludes kinds used by other rows but keeps the row´s own kind', () => {
    const rows = [
      row({ key: 1, priceKind: PriceKind.Regular }),
      row({ key: 2, priceKind: PriceKind.ClubCard }),
    ];
    const available = availablePriceKinds(rows, PriceKind.ClubCard);
    expect(available).toContain(PriceKind.ClubCard);
    expect(available).not.toContain(PriceKind.Regular);
  });
});

describe('newPriceRow', () => {
  it('picks the first price kind not yet used in the batch', () => {
    const created = newPriceRow(2, [row({ key: 1, priceKind: PriceKind.Regular })]);
    expect(created.priceKind).not.toBe(PriceKind.Regular);
    expect(created.key).toBe(2);
    expect(created.priceAmount).toBeNull();
  });
});
