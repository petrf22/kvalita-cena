/** Publikum, kterému lze zpřístupnit konkrétní pole profilu. */
export const Audience = {
  Friends: 'FRIENDS',
  Public: 'PUBLIC'
} as const;

export type Audience = typeof Audience[keyof typeof Audience];
export const CatalogDataSource = {
  Community: 'COMMUNITY',
  OpenFoodFacts: 'OPEN_FOOD_FACTS'
} as const;

export type CatalogDataSource = typeof CatalogDataSource[keyof typeof CatalogDataSource];
export const ChainType = {
  Chain: 'CHAIN',
  FarmShop: 'FARM_SHOP',
  Independent: 'INDEPENDENT',
  Market: 'MARKET',
  Online: 'ONLINE'
} as const;

export type ChainType = typeof ChainType[keyof typeof ChainType];
/** Odkud přišel request (core.feedback.client_kind) — odvozeno server-side z X-Client-Kind, nikdy z inputu. */
export const ClientKind = {
  Android: 'ANDROID',
  Web: 'WEB'
} as const;

export type ClientKind = typeof ClientKind[keyof typeof ClientKind];
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
  AccountDeleteInvalidChallenge: 'ACCOUNT_DELETE_INVALID_CHALLENGE',
  AccountDeleteRequiresLogin: 'ACCOUNT_DELETE_REQUIRES_LOGIN',
  AccountGone: 'ACCOUNT_GONE',
  AccountSuspended: 'ACCOUNT_SUSPENDED',
  AvatarRequiresLogin: 'AVATAR_REQUIRES_LOGIN',
  CategoryNotFound: 'CATEGORY_NOT_FOUND',
  ChainNotFound: 'CHAIN_NOT_FOUND',
  ClientVersionTooOld: 'CLIENT_VERSION_TOO_OLD',
  CompanyIdInvalid: 'COMPANY_ID_INVALID',
  ContributionsRequireLogin: 'CONTRIBUTIONS_REQUIRE_LOGIN',
  DuplicateGenericProduct: 'DUPLICATE_GENERIC_PRODUCT',
  DuplicateProductCode: 'DUPLICATE_PRODUCT_CODE',
  DuplicateStore: 'DUPLICATE_STORE',
  EmailChangeEmailTaken: 'EMAIL_CHANGE_EMAIL_TAKEN',
  EmailChangeInvalidChallenge: 'EMAIL_CHANGE_INVALID_CHALLENGE',
  EmailChangeRequiresLogin: 'EMAIL_CHANGE_REQUIRES_LOGIN',
  EmailChangeSameAddress: 'EMAIL_CHANGE_SAME_ADDRESS',
  FeedbackContactEmailInvalid: 'FEEDBACK_CONTACT_EMAIL_INVALID',
  FeedbackMessageRequired: 'FEEDBACK_MESSAGE_REQUIRED',
  FeedbackMessageTooLong: 'FEEDBACK_MESSAGE_TOO_LONG',
  FeedbackNotFound: 'FEEDBACK_NOT_FOUND',
  FlagRecordNotFound: 'FLAG_RECORD_NOT_FOUND',
  FlagRequiresLogin: 'FLAG_REQUIRES_LOGIN',
  InvalidChallenge: 'INVALID_CHALLENGE',
  LocaleRequiresLogin: 'LOCALE_REQUIRES_LOGIN',
  LocaleUnsupported: 'LOCALE_UNSUPPORTED',
  ModerationObservationNotFound: 'MODERATION_OBSERVATION_NOT_FOUND',
  ModerationRecordNotFound: 'MODERATION_RECORD_NOT_FOUND',
  ModerationRequiresRole: 'MODERATION_REQUIRES_ROLE',
  ModerationUserNotFound: 'MODERATION_USER_NOT_FOUND',
  ObservationAlreadySubmittedToday: 'OBSERVATION_ALREADY_SUBMITTED_TODAY',
  ObservationDuplicatePriceKind: 'OBSERVATION_DUPLICATE_PRICE_KIND',
  ObservationPricesRequired: 'OBSERVATION_PRICES_REQUIRED',
  ObservationPriceIncomplete: 'OBSERVATION_PRICE_INCOMPLETE',
  ObservationPriceKindAlreadySubmittedToday: 'OBSERVATION_PRICE_KIND_ALREADY_SUBMITTED_TODAY',
  ObservationPromoValidityNotAllowed: 'OBSERVATION_PROMO_VALIDITY_NOT_ALLOWED',
  ObservationPromoValidityRangeInvalid: 'OBSERVATION_PROMO_VALIDITY_RANGE_INVALID',
  ObservationPromoValidFromInFuture: 'OBSERVATION_PROMO_VALID_FROM_IN_FUTURE',
  OffProductNotFound: 'OFF_PRODUCT_NOT_FOUND',
  OffUnavailable: 'OFF_UNAVAILABLE',
  PhotoActionRequiresLogin: 'PHOTO_ACTION_REQUIRES_LOGIN',
  PhotoCannotAttachToPhoto: 'PHOTO_CANNOT_ATTACH_TO_PHOTO',
  PhotoCannotAttachToUser: 'PHOTO_CANNOT_ATTACH_TO_USER',
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
  ProfileDisplayNameTooLong: 'PROFILE_DISPLAY_NAME_TOO_LONG',
  ProfileEmailInvalid: 'PROFILE_EMAIL_INVALID',
  ProfileNameTooLong: 'PROFILE_NAME_TOO_LONG',
  ProfilePhoneInvalid: 'PROFILE_PHONE_INVALID',
  ProfileRequiresLogin: 'PROFILE_REQUIRES_LOGIN',
  QualityGradeOutOfRange: 'QUALITY_GRADE_OUT_OF_RANGE',
  QualityRequiresLogin: 'QUALITY_REQUIRES_LOGIN',
  SessionExpired: 'SESSION_EXPIRED',
  StoreCityEmpty: 'STORE_CITY_EMPTY',
  StoreCityRequired: 'STORE_CITY_REQUIRED',
  StoreCountryEditRequiresTrust: 'STORE_COUNTRY_EDIT_REQUIRES_TRUST',
  StoreCreateRequiresLogin: 'STORE_CREATE_REQUIRES_LOGIN',
  StoreEditRequiresLogin: 'STORE_EDIT_REQUIRES_LOGIN',
  StoreNameEmpty: 'STORE_NAME_EMPTY',
  StoreNameRequired: 'STORE_NAME_REQUIRED',
  StoreNotFound: 'STORE_NOT_FOUND',
  StoreUrlInvalid: 'STORE_URL_INVALID',
  TermsAcceptanceRequired: 'TERMS_ACCEPTANCE_REQUIRED',
  TooManyRequests: 'TOO_MANY_REQUESTS',
  UnsupportedCountry: 'UNSUPPORTED_COUNTRY',
  UomMismatch: 'UOM_MISMATCH',
  ValidationFailed: 'VALIDATION_FAILED'
} as const;

export type ErrorCode = typeof ErrorCode[keyof typeof ErrorCode];
export const ExternalLinkKind = {
  ENumbers: 'E_NUMBERS',
  OpenFoodFacts: 'OPEN_FOOD_FACTS'
} as const;

export type ExternalLinkKind = typeof ExternalLinkKind[keyof typeof ExternalLinkKind];
/** Kategorie zpětné vazby (core.feedback) — jen k roztřídění fronty, nemění chování. */
export const FeedbackCategory = {
  Bug: 'BUG',
  Content: 'CONTENT',
  Idea: 'IDEA',
  Other: 'OTHER'
} as const;

export type FeedbackCategory = typeof FeedbackCategory[keyof typeof FeedbackCategory];
/** Výsledek moderátorského přezkumu nahlášeného záznamu (docs/reputace.md, 'Moderace'). */
export const FlagResolution = {
  /** Nahlášení bylo neopodstatněné — hidden_at cíle se vrátí na NULL. */
  Dismissed: 'DISMISSED',
  /** Nahlášení bylo oprávněné — cíl zůstává (nebo se nově nastaví) skrytý. */
  Upheld: 'UPHELD'
} as const;

export type FlagResolution = typeof FlagResolution[keyof typeof FlagResolution];
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
/**
 * Druh fotky (core.media.photo_kind) — nezávislá osa od RecordType (ten říká čí je fotka, tohle
 * co na ní je). ITEM, ne PRODUCT — RecordType.PRODUCT už znamená totéž na jiné ose. Posílá se při
 * uploadu jako parametr kind REST endpointu POST /api/media/{recordType}/{recordId}.
 */
export const PhotoKind = {
  /** Fotka samotného zboží/obalu. */
  Item: 'ITEM',
  /** Fotka etikety — zamýšlený budoucí vstup pro čtení složení/textu z etikety (docs/ai.md). */
  Label: 'LABEL',
  /** Cokoli jiného, včetně fotek provozoven a avatarů (druh nerozlišují, zůstávají na OTHER). */
  Other: 'OTHER'
} as const;

export type PhotoKind = typeof PhotoKind[keyof typeof PhotoKind];
export const PriceKind = {
  Clearance: 'CLEARANCE',
  ClubCard: 'CLUB_CARD',
  Multibuy: 'MULTIBUY',
  Promo: 'PROMO',
  Regular: 'REGULAR'
} as const;

export type PriceKind = typeof PriceKind[keyof typeof PriceKind];
export const ProductLookupStatus = {
  Existing: 'EXISTING',
  NotFound: 'NOT_FOUND',
  OffCandidate: 'OFF_CANDIDATE',
  OffUnavailable: 'OFF_UNAVAILABLE'
} as const;

export type ProductLookupStatus = typeof ProductLookupStatus[keyof typeof ProductLookupStatus];
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
/** Pole profilu, pro které lze zvlášť zapnout viditelnost vůči Audience. */
export const ProfileField = {
  Avatar: 'AVATAR',
  ContactEmail: 'CONTACT_EMAIL',
  DisplayName: 'DISPLAY_NAME',
  FirstName: 'FIRST_NAME',
  LastName: 'LAST_NAME',
  Phone: 'PHONE'
} as const;

export type ProfileField = typeof ProfileField[keyof typeof ProfileField];
/**
 * Viditelnost profilu (auth.user_profile.visibility) — výchozí ANONYMOUS, aby si lidé ze
 * setrvačnosti nedávali skutečné jméno (docs/soukromi.md). U PUBLIC/FRIENDS teprve rozhoduje
 * Profile.visibleFields, KTERÁ pole se komu zobrazí.
 */
export const ProfileVisibility = {
  Anonymous: 'ANONYMOUS',
  Friends: 'FRIENDS',
  Public: 'PUBLIC'
} as const;

export type ProfileVisibility = typeof ProfileVisibility[keyof typeof ProfileVisibility];
/**
 * Kdy se vlastní záznam propaguje globálně (docs/datovy-model.md, "Uživatelská vrstva nad
 * globálními daty"; prahy v docs/reputace.md) — jeden zdroj pravdy pro "Moje příspěvky", ať
 * klient neumí zobrazit protichůdný text na dvou různých obrazovkách.
 */
export const PublicationState = {
  /** DRAFT zboží / PENDING obchod — v hledání zatím vidí jen autor, dokud ho nepotvrdí jiní přispěvatelé. */
  AwaitingConfirmations: 'AWAITING_CONFIRMATIONS',
  /** hidden_at != null po nahlášení (core.record_flag) — čeká na přezkum, vidí ho dál jen autor. */
  HiddenAfterFlags: 'HIDDEN_AFTER_FLAGS',
  /** Patch v core.product_user_edit/core.store_user_edit — konsolidační job zatím neběží, vidí ho jen autor. */
  PendingMerge: 'PENDING_MERGE',
  /** Vidí každý (status ACTIVE). */
  Public: 'PUBLIC'
} as const;

export type PublicationState = typeof PublicationState[keyof typeof PublicationState];
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