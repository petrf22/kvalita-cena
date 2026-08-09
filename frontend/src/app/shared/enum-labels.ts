import { PriceKind, QuantityBasis } from '../models/catalog';

/**
 * Popisky enumů na jedno místo — dřív byly `PRICE_KIND_LABELS` duplicitně v
 * `features/price-entry/price-entry-page.ts` i `features/product-detail/product-detail-page.ts`
 * (a `MULTIBUY` chyběl v obou <nz-select>, i když ho enum i popisky měly). `Record<PriceKind,
 * string>` nad generovaným enumem (models/generated/enums.ts) shodí kompilaci, jakmile schéma
 * přidá novou hodnotu a popisek k ní chybí.
 */
export const PRICE_KIND_LABELS: Record<PriceKind, string> = {
  [PriceKind.Regular]: 'Běžná cena',
  [PriceKind.Promo]: 'Akce',
  [PriceKind.ClubCard]: 'Klubová karta',
  [PriceKind.Clearance]: 'Výprodej',
  [PriceKind.Multibuy]: 'Množstevní sleva',
};

export const QUANTITY_BASIS_LABELS: Record<QuantityBasis, string> = {
  [QuantityBasis.Package]: 'Za balení',
  [QuantityBasis.PerKg]: 'Za kilogram',
  [QuantityBasis.PerL]: 'Za litr',
  [QuantityBasis.PerPiece]: 'Za kus',
};

/**
 * Druhy ceny nabízené ve formuláři zápisu. MULTIBUY tu schválně chybí — schéma pro něj
 * vyžaduje i multibuyQty/multibuyTotal (`docs/datovy-model.md`) a formuláře je zatím neumí
 * zadat (etapa 2/3, viz CLAUDE.md).
 */
export const SELECTABLE_PRICE_KINDS: readonly PriceKind[] = [
  PriceKind.Regular,
  PriceKind.Promo,
  PriceKind.ClubCard,
  PriceKind.Clearance,
];

/** Druhy uvedení ceny nabízené pro váhové zboží (isVariableWeight) — bez PACKAGE, to je výchozí pro ostatní. */
export const SELECTABLE_VARIABLE_WEIGHT_QUANTITY_BASES: readonly QuantityBasis[] = [
  QuantityBasis.PerKg,
  QuantityBasis.PerL,
  QuantityBasis.PerPiece,
];
