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
/**
 * Strojově čitelný kód chyby (docs/lokalizace.md) — jde jen do GraphQL extensions.code, NIKDY
 * do žádného pole typu. Existuje ve schématu čistě proto, aby ho graphql-codegen vygeneroval
 * jako TS konstanty (frontend/codegen.ts, onlyEnums) — frontend nad ním postaví Record<ErrorCode,
 * string>, stejný trik jako PRICE_KIND_LABELS, takže chybějící překlad kódu shodí kompilaci.
 * Musí se shodovat s cz.kvalitacena.exception.ErrorCode (viz backendový test MessageBundleTest).
 */
export const ErrorCode = {
  AccountGone: 'ACCOUNT_GONE',
  CategoryNotFound: 'CATEGORY_NOT_FOUND',
  ChainNotFound: 'CHAIN_NOT_FOUND',
  CompanyIdInvalid: 'COMPANY_ID_INVALID',
  DuplicateGenericProduct: 'DUPLICATE_GENERIC_PRODUCT',
  DuplicateProductCode: 'DUPLICATE_PRODUCT_CODE',
  DuplicateStore: 'DUPLICATE_STORE',
  FlagRecordNotFound: 'FLAG_RECORD_NOT_FOUND',
  FlagRequiresLogin: 'FLAG_REQUIRES_LOGIN',
  InvalidChallenge: 'INVALID_CHALLENGE',
  LocaleRequiresLogin: 'LOCALE_REQUIRES_LOGIN',
  LocaleUnsupported: 'LOCALE_UNSUPPORTED',
  PhotoActionRequiresLogin: 'PHOTO_ACTION_REQUIRES_LOGIN',
  PhotoCannotAttachToPhoto: 'PHOTO_CANNOT_ATTACH_TO_PHOTO',
  PhotoDeleteNotOwner: 'PHOTO_DELETE_NOT_OWNER',
  PhotoLimitReached: 'PHOTO_LIMIT_REACHED',
  PhotoNotFound: 'PHOTO_NOT_FOUND',
  PhotoResolutionTooHigh: 'PHOTO_RESOLUTION_TOO_HIGH',
  PhotoTargetRecordNotFound: 'PHOTO_TARGET_RECORD_NOT_FOUND',
  PhotoTooLarge: 'PHOTO_TOO_LARGE',
  PhotoUnreadable: 'PHOTO_UNREADABLE',
  PhotoUnsupportedFormat: 'PHOTO_UNSUPPORTED_FORMAT',
  PhotoUpdateNotOwner: 'PHOTO_UPDATE_NOT_OWNER',
  PhotoUploadRequiresLogin: 'PHOTO_UPLOAD_REQUIRES_LOGIN',
  ProductCategoryRequired: 'PRODUCT_CATEGORY_REQUIRED',
  ProductCreateRequiresLogin: 'PRODUCT_CREATE_REQUIRES_LOGIN',
  ProductEditRequiresLogin: 'PRODUCT_EDIT_REQUIRES_LOGIN',
  ProductNameEmpty: 'PRODUCT_NAME_EMPTY',
  ProductNameRequired: 'PRODUCT_NAME_REQUIRED',
  ProductNotFound: 'PRODUCT_NOT_FOUND',
  ProductUnitBaseRequired: 'PRODUCT_UNIT_BASE_REQUIRED',
  QualityGradeOutOfRange: 'QUALITY_GRADE_OUT_OF_RANGE',
  QualityRequiresLogin: 'QUALITY_REQUIRES_LOGIN',
  SessionExpired: 'SESSION_EXPIRED',
  StoreCityEmpty: 'STORE_CITY_EMPTY',
  StoreCityRequired: 'STORE_CITY_REQUIRED',
  StoreCreateRequiresLogin: 'STORE_CREATE_REQUIRES_LOGIN',
  StoreEditRequiresLogin: 'STORE_EDIT_REQUIRES_LOGIN',
  StoreNameEmpty: 'STORE_NAME_EMPTY',
  StoreNameRequired: 'STORE_NAME_REQUIRED',
  StoreNotFound: 'STORE_NOT_FOUND',
  TooManyRequests: 'TOO_MANY_REQUESTS',
  UomMismatch: 'UOM_MISMATCH',
  ValidationFailed: 'VALIDATION_FAILED'
} as const;

export type ErrorCode = typeof ErrorCode[keyof typeof ErrorCode];
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