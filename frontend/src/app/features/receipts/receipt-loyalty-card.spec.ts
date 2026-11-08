import { afterEach, describe, expect, it } from 'vitest';
import {
  hasDiscountLine,
  loyaltyCardKey,
  rememberLoyaltyCard,
  rememberedLoyaltyCard,
} from './receipt-loyalty-card';

describe('loyaltyCardKey', () => {
  it('karta patří řetězci, samostatná prodejna má vlastní klíč', () => {
    expect(loyaltyCardKey({ id: '7', chain: { id: '3' } })).toBe('chain:3');
    expect(loyaltyCardKey({ id: '7', chain: null })).toBe('store:7');
    expect(loyaltyCardKey(null)).toBeNull();
  });
});

describe('hasDiscountLine', () => {
  it('jen kladná sleva se počítá', () => {
    expect(hasDiscountLine([{ discountAmount: null }, { discountAmount: 0 }])).toBe(false);
    expect(hasDiscountLine([{ discountAmount: null }, { discountAmount: 5 }])).toBe(true);
  });
});

describe('zapamatovaná volba', () => {
  afterEach(() => localStorage.clear());

  it('vrátí poslední volbu pro řetězec, bez ní nic', () => {
    expect(rememberedLoyaltyCard('chain:3')).toBeNull();
    rememberLoyaltyCard('chain:3', true);
    expect(rememberedLoyaltyCard('chain:3')).toBe(true);
    rememberLoyaltyCard('chain:3', false);
    expect(rememberedLoyaltyCard('chain:3')).toBe(false);
    expect(rememberedLoyaltyCard('chain:4')).toBeNull();
  });
});
