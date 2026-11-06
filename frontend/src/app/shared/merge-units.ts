import type { ProductSummary } from '../models/catalog';
import { UnitBase } from '../models/generated/enums';

/** Co o zboží potřebuje vědět pravidlo jednotek — pole z `ProductSummaryFields`. */
export type UnitSource = Pick<ProductSummary, 'unitBase' | 'netContentValue' | 'isVariableWeight'>;

/** Co znamená jednotková cena zboží: za kg, za litr, za kus, nebo za balení neznámé velikosti. */
type PriceUnit = 'MASS' | 'VOLUME' | 'COUNT' | 'PACKAGE';

function priceUnit(product: UnitSource): PriceUnit {
  if (product.isVariableWeight) return product.unitBase === UnitBase.Volume ? 'VOLUME' : 'MASS';
  if (product.netContentValue == null) return 'PACKAGE';
  return product.unitBase === UnitBase.Mass
    ? 'MASS'
    : product.unitBase === UnitBase.Volume
      ? 'VOLUME'
      : 'COUNT';
}

/**
 * Jdou ceny zdroje přesunout na cíl? Platí pro dopárování zboží z účtenky i moderátorské sloučení
 * duplicit (server `ProductMergeUnits`, `RECEIPT_ITEM_REMATCH_UNIT_MISMATCH` /
 * `MODERATION_PRODUCT_MERGE_UNIT_MISMATCH`): stejný význam, nebo cena za balení neznámé velikosti
 * na zboží s pevnou gramáží (balení převezme gramáž cíle). Cena za kus na váhové zboží ani cena
 * za kg na zboží bez gramáže převést nejde. Server rozhoduje i podle jednotlivých cen, tohle je
 * jen předvýběr nabídky.
 */
export function mergeUnitsFit(source: UnitSource, target: UnitSource): boolean {
  const from = priceUnit(source);
  const to = priceUnit(target);
  return from === to || (from === 'PACKAGE' && !target.isVariableWeight && to !== 'PACKAGE');
}
