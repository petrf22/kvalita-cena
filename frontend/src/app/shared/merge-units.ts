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
 * Jdou ceny zdroje přesunout na cíl? Zjednodušené pravidlo serveru (`ProductMergeUnits`,
 * `RECEIPT_ITEM_REMATCH_UNIT_MISMATCH`): stejný význam, nebo cena za balení neznámé velikosti
 * na zboží s pevnou gramáží (balení převezme gramáž cíle). Cena za kus na váhové zboží ani cena
 * za kg na zboží bez gramáže převést nejde.
 *
 * Je to jen předvýběr nabídky dopárování zboží z účtenky — to má vždy ceny. Server rozhoduje
 * podle jednotlivých cen a gramáže z patche jejich autora, proto moderátorské slučování (zdroj
 * může být bez cen) tohle pravidlo nepoužívá a nechá rozhodnout server.
 */
export function mergeUnitsFit(source: UnitSource, target: UnitSource): boolean {
  const from = priceUnit(source);
  const to = priceUnit(target);
  return from === to || (from === 'PACKAGE' && !target.isVariableWeight && to !== 'PACKAGE');
}
