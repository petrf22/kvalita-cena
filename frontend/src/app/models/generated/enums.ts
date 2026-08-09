export const ChainType = {
  Chain: 'CHAIN',
  FarmShop: 'FARM_SHOP',
  Independent: 'INDEPENDENT',
  Market: 'MARKET',
  Online: 'ONLINE'
} as const;

export type ChainType = typeof ChainType[keyof typeof ChainType];
export const CodeType = {
  Gtin: 'GTIN',
  Plu: 'PLU',
  StoreInternal: 'STORE_INTERNAL'
} as const;

export type CodeType = typeof CodeType[keyof typeof CodeType];
export const Confidence = {
  High: 'HIGH',
  Low: 'LOW',
  Medium: 'MEDIUM'
} as const;

export type Confidence = typeof Confidence[keyof typeof Confidence];
export const ExternalLinkKind = {
  ENumbers: 'E_NUMBERS',
  OpenFoodFacts: 'OPEN_FOOD_FACTS'
} as const;

export type ExternalLinkKind = typeof ExternalLinkKind[keyof typeof ExternalLinkKind];
export const GeoSource = {
  /** Souřadnice zadal/potvrdil uživatel (ručně, nebo výběrem geokódovaného kandidáta). */
  Community: 'COMMUNITY',
  /** Souřadnice převzaté z OpenStreetMap Nominatim — jen lat/lon a osm_ref, nic dalšího z OSM. */
  Osm: 'OSM'
} as const;

export type GeoSource = typeof GeoSource[keyof typeof GeoSource];
export const NetContentUom = {
  G: 'G',
  Kg: 'KG',
  L: 'L',
  Ml: 'ML',
  Pcs: 'PCS'
} as const;

export type NetContentUom = typeof NetContentUom[keyof typeof NetContentUom];
export const ObservationStatus = {
  Active: 'ACTIVE',
  Disputed: 'DISPUTED',
  Pending: 'PENDING',
  Rejected: 'REJECTED'
} as const;

export type ObservationStatus = typeof ObservationStatus[keyof typeof ObservationStatus];
export const PriceKind = {
  Clearance: 'CLEARANCE',
  ClubCard: 'CLUB_CARD',
  Multibuy: 'MULTIBUY',
  Promo: 'PROMO',
  Regular: 'REGULAR'
} as const;

export type PriceKind = typeof PriceKind[keyof typeof PriceKind];
export const ProductSort = {
  LastReported: 'LAST_REPORTED',
  Name: 'NAME',
  PriceAsc: 'PRICE_ASC',
  /** 1 = nejlepší, tedy vzestupně. */
  Quality: 'QUALITY',
  /** Výchozí — nejvíc potvrzené zboží nahoře (součet agg.price_current.n_obs). */
  ReportCount: 'REPORT_COUNT'
} as const;

export type ProductSort = typeof ProductSort[keyof typeof ProductSort];
export const ProductStatus = {
  Active: 'ACTIVE',
  Draft: 'DRAFT',
  Merged: 'MERGED',
  Rejected: 'REJECTED'
} as const;

export type ProductStatus = typeof ProductStatus[keyof typeof ProductStatus];
export const QuantityBasis = {
  Package: 'PACKAGE',
  PerKg: 'PER_KG',
  PerL: 'PER_L',
  PerPiece: 'PER_PIECE'
} as const;

export type QuantityBasis = typeof QuantityBasis[keyof typeof QuantityBasis];
export const RecordType = {
  /** Nahlašovaný typ pro flagRecord — core.media samo nese jen PRODUCT/STORE (čí je fotka). */
  Photo: 'PHOTO',
  Product: 'PRODUCT',
  Store: 'STORE'
} as const;

export type RecordType = typeof RecordType[keyof typeof RecordType];
export const UnitBase = {
  Count: 'COUNT',
  Mass: 'MASS',
  Volume: 'VOLUME'
} as const;

export type UnitBase = typeof UnitBase[keyof typeof UnitBase];