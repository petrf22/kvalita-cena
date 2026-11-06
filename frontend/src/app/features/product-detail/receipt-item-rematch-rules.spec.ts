import { describe, expect, it } from 'vitest';
import type { Product, ProductSummary } from '../../models/catalog';
import {
  rematchCandidates,
  rematchInitialQuery,
  rematchScopeName,
  rematchStoreId,
  rematchTargetFits,
} from './receipt-item-rematch-rules';

type Scoped = Pick<
  ProductSummary,
  | 'id'
  | 'catalogScope'
  | 'scopeChain'
  | 'scopeStore'
  | 'unitBase'
  | 'netContentValue'
  | 'isVariableWeight'
>;

/** Cena za balení neznámé velikosti — výchozí zboží z účtenky i bezkódové zboží bez gramáže. */
const packageUnit = { unitBase: 'COUNT', netContentValue: null, isVariableWeight: false } as const;

const chain = (id: string, name = `Řetězec ${id}`) =>
  ({ id, name, chainType: 'CHAIN' }) as NonNullable<ProductSummary['scopeChain']>;
const store = (id: string, chainId: string | null = null) =>
  ({
    id,
    name: `Obchod ${id}`,
    chain: chainId ? chain(chainId) : null,
  }) as unknown as NonNullable<ProductSummary['scopeStore']>;

const global = (id: string): Scoped => ({
  id,
  catalogScope: 'GLOBAL',
  scopeChain: null,
  scopeStore: null,
  ...packageUnit,
});
const chainScoped = (id: string, chainId: string): Scoped => ({
  id,
  catalogScope: 'CHAIN',
  scopeChain: chain(chainId),
  scopeStore: null,
  ...packageUnit,
});
const storeScoped = (id: string, storeId: string): Scoped => ({
  id,
  catalogScope: 'STORE',
  scopeChain: null,
  scopeStore: store(storeId),
  ...packageUnit,
});

describe('cíl dopárování zboží z účtenky', () => {
  it('globální zboží jde vždycky, i staré globální', () => {
    expect(rematchTargetFits(chainScoped('s', 'c1'), global('t'))).toBe(true);
    expect(
      rematchTargetFits(storeScoped('s', 'o1'), { ...global('t'), catalogScope: 'LEGACY_GLOBAL' }),
    ).toBe(true);
  });

  it('lokální zboží jen ze stejného řetězce, resp. téže provozovny', () => {
    expect(rematchTargetFits(chainScoped('s', 'c1'), chainScoped('t', 'c1'))).toBe(true);
    expect(rematchTargetFits(chainScoped('s', 'c1'), chainScoped('t', 'c2'))).toBe(false);
    expect(rematchTargetFits(storeScoped('s', 'o1'), storeScoped('t', 'o1'))).toBe(true);
    expect(rematchTargetFits(storeScoped('s', 'o1'), storeScoped('t', 'o2'))).toBe(false);
  });

  it('zboží jedné provozovny nejde na zboží řetězce ani naopak', () => {
    expect(rematchTargetFits(chainScoped('s', 'c1'), storeScoped('t', 'o1'))).toBe(false);
    expect(rematchTargetFits(storeScoped('s', 'o1'), chainScoped('t', 'c1'))).toBe(false);
  });

  it('zboží z účtenky s EAN je globální a jde jen na globální zboží', () => {
    expect(rematchTargetFits(global('s'), global('t'))).toBe(true);
    expect(rematchTargetFits(global('s'), chainScoped('t', 'c1'))).toBe(false);
    expect(rematchTargetFits(global('s'), storeScoped('t', 'o1'))).toBe(false);
  });

  it('nabídka vynechá zboží samotné a zboží mimo rozsah', () => {
    const source = chainScoped('s', 'c1');
    const offered = [source, global('g'), chainScoped('a', 'c1'), chainScoped('b', 'c2')];
    expect(rematchCandidates(source, offered).map((p) => p.id)).toEqual(['g', 'a']);
  });
});

describe('obchod pro nabídku cílů', () => {
  const product = (
    scoped: Scoped,
    priceStores: ReturnType<typeof store>[] = [],
    myPriceStores: ReturnType<typeof store>[] = [],
  ) =>
    ({
      ...scoped,
      prices: priceStores.map((s) => ({ store: s })),
      myPrices: myPriceStores.map((s) => ({ store: s })),
    }) as unknown as Product;

  it('u provozovny ona sama', () => {
    expect(rematchStoreId(product(storeScoped('s', 'o1')))).toBe('o1');
  });

  it('u řetězce provozovna téhož řetězce, kde má zboží cenu', () => {
    const p = product(chainScoped('s', 'c1'), [store('x', 'c2'), store('y', 'c1')]);
    expect(rematchStoreId(p)).toBe('y');
  });

  it('když ceny ještě neprošly agregací, poslouží vlastní zápis', () => {
    expect(rematchStoreId(product(chainScoped('s', 'c1'), [], [store('z', 'c1')]))).toBe('z');
  });

  it('bez známé provozovny se hledá napříč katalogem', () => {
    expect(rematchStoreId(product(chainScoped('s', 'c1')))).toBeNull();
    expect(rematchStoreId(product(global('s')))).toBeNull();
  });

  it('název rozsahu pro vysvětlující větu', () => {
    expect(rematchScopeName(chainScoped('s', 'c1'))).toBe('Řetězec c1');
    expect(rematchScopeName(storeScoped('s', 'o1'))).toBe('Obchod o1');
  });
});

describe('jednotky cen v nabídce dopárování', () => {
  const perKg = { unitBase: 'MASS', netContentValue: null, isVariableWeight: true } as const;

  it('nabídka vynechá zboží s nepřevoditelnou jednotkou', () => {
    const source = chainScoped('s', 'c1');
    const weighed = { ...chainScoped('w', 'c1'), ...perKg };
    expect(rematchCandidates(source, [weighed, chainScoped('t', 'c1')]).map((p) => p.id)).toEqual([
      't',
    ]);
  });
});

describe('výchozí dotaz dopárování', () => {
  it('vynechá gramáž a počet kusů', () => {
    expect(rematchInitialQuery('KECUP JEMNY 500G')).toBe('KECUP JEMNY');
    expect(rematchInitialQuery('COCA COLA 1,5L')).toBe('COCA COLA');
    expect(rematchInitialQuery('VODA 6X1.5L')).toBe('VODA');
  });

  it('zkratku z účtenky rozdělí místo interpunkce', () => {
    expect(rematchInitialQuery('TUK.ROHLIK')).toBe('TUK ROHLIK');
  });

  it('když nic nezbude, nechá název, jak je', () => {
    expect(rematchInitialQuery('500G')).toBe('500G');
  });
});
