import {
  ClientKind,
  Confidence,
  ExternalLinkKind,
  FeedbackCategory,
  GeoSource,
  NetContentUom,
  PriceKind,
  ProductSort,
  QuantityBasis,
  RecordType,
  UnitBase,
} from '../models/catalog';
import { ErrorCode } from '../models/generated/enums';

/**
 * Překladové KLÍČE (ne texty) na jedno místo — `Record<PriceKind, string>` nad generovaným
 * enumem (models/generated/enums.ts) pořád shodí kompilaci, jakmile schéma přidá hodnotu bez
 * klíče. Že klíč skutečně existuje ve všech čtyřech jazycích, hlídá `i18n.spec.ts`, ne
 * kompilátor — obě kontroly dohromady jsou "druhá polovina triku" (docs/lokalizace.md).
 * V šablonách se používá s `TranslocoPipe`, např. `{{ PRICE_KIND_KEYS[mp.priceKind] | transloco }}`.
 */
export const PRICE_KIND_KEYS: Record<PriceKind, string> = {
  [PriceKind.Regular]: 'enum.priceKind.REGULAR',
  [PriceKind.Promo]: 'enum.priceKind.PROMO',
  [PriceKind.ClubCard]: 'enum.priceKind.CLUB_CARD',
  [PriceKind.Clearance]: 'enum.priceKind.CLEARANCE',
  [PriceKind.Multibuy]: 'enum.priceKind.MULTIBUY',
};

export const QUANTITY_BASIS_KEYS: Record<QuantityBasis, string> = {
  [QuantityBasis.Package]: 'enum.quantityBasis.PACKAGE',
  [QuantityBasis.PerKg]: 'enum.quantityBasis.PER_KG',
  [QuantityBasis.PerL]: 'enum.quantityBasis.PER_L',
  [QuantityBasis.PerPiece]: 'enum.quantityBasis.PER_PIECE',
};

export const UNIT_BASE_KEYS: Record<UnitBase, string> = {
  [UnitBase.Mass]: 'enum.unitBase.MASS',
  [UnitBase.Volume]: 'enum.unitBase.VOLUME',
  [UnitBase.Count]: 'enum.unitBase.COUNT',
};

export const PRODUCT_SORT_KEYS: Record<ProductSort, string> = {
  [ProductSort.ReportCount]: 'enum.productSort.REPORT_COUNT',
  [ProductSort.PriceAsc]: 'enum.productSort.PRICE_ASC',
  [ProductSort.Quality]: 'enum.productSort.QUALITY',
  [ProductSort.LastReported]: 'enum.productSort.LAST_REPORTED',
  [ProductSort.Name]: 'enum.productSort.NAME',
};

export const CONFIDENCE_KEYS: Record<Confidence, string> = {
  [Confidence.High]: 'enum.confidence.HIGH',
  [Confidence.Medium]: 'enum.confidence.MEDIUM',
  [Confidence.Low]: 'enum.confidence.LOW',
};

export const NET_CONTENT_UOM_KEYS: Record<NetContentUom, string> = {
  [NetContentUom.G]: 'enum.netContentUom.G',
  [NetContentUom.Kg]: 'enum.netContentUom.KG',
  [NetContentUom.Ml]: 'enum.netContentUom.ML',
  [NetContentUom.L]: 'enum.netContentUom.L',
  [NetContentUom.Pcs]: 'enum.netContentUom.PCS',
};

export const EXTERNAL_LINK_KIND_KEYS: Record<ExternalLinkKind, string> = {
  [ExternalLinkKind.OpenFoodFacts]: 'enum.externalLinkKind.OPEN_FOOD_FACTS',
  [ExternalLinkKind.ENumbers]: 'enum.externalLinkKind.E_NUMBERS',
};

export const GEO_SOURCE_KEYS: Record<GeoSource, string> = {
  [GeoSource.Community]: 'enum.geoSource.COMMUNITY',
  [GeoSource.Osm]: 'enum.geoSource.OSM',
};

export const RECORD_TYPE_KEYS: Record<RecordType, string> = {
  [RecordType.Product]: 'enum.recordType.PRODUCT',
  [RecordType.Store]: 'enum.recordType.STORE',
  [RecordType.Photo]: 'enum.recordType.PHOTO',
};

/** Kategorie zpětné vazby (core.feedback) — nabídka ve formuláři i popisek ve frontě moderace. */
export const FEEDBACK_CATEGORY_KEYS: Record<FeedbackCategory, string> = {
  [FeedbackCategory.Bug]: 'enum.feedbackCategory.BUG',
  [FeedbackCategory.Idea]: 'enum.feedbackCategory.IDEA',
  [FeedbackCategory.Content]: 'enum.feedbackCategory.CONTENT',
  [FeedbackCategory.Other]: 'enum.feedbackCategory.OTHER',
};

/** Odkud hlášení přišlo (core.feedback.client_kind) — jen popisek ve frontě moderace. */
export const CLIENT_KIND_KEYS: Record<ClientKind, string> = {
  [ClientKind.Web]: 'enum.clientKind.WEB',
  [ClientKind.Android]: 'enum.clientKind.ANDROID',
};

/** Díky `enum ErrorCode` ve `schema.graphqls` (docs/lokalizace.md) generuje codegen i tohle zadarmo. */
export const ERROR_CODE_KEYS: Record<ErrorCode, string> = {
  [ErrorCode.OffUnavailable]: 'errors.OFF_UNAVAILABLE',
  [ErrorCode.OffProductNotFound]: 'errors.OFF_PRODUCT_NOT_FOUND',
  [ErrorCode.ProductNotFound]: 'errors.PRODUCT_NOT_FOUND',
  [ErrorCode.StoreNotFound]: 'errors.STORE_NOT_FOUND',
  [ErrorCode.CategoryNotFound]: 'errors.CATEGORY_NOT_FOUND',
  [ErrorCode.ChainNotFound]: 'errors.CHAIN_NOT_FOUND',
  [ErrorCode.ProductNameRequired]: 'errors.PRODUCT_NAME_REQUIRED',
  [ErrorCode.ProductNameEmpty]: 'errors.PRODUCT_NAME_EMPTY',
  [ErrorCode.ProductCategoryRequired]: 'errors.PRODUCT_CATEGORY_REQUIRED',
  [ErrorCode.ProductUnitBaseRequired]: 'errors.PRODUCT_UNIT_BASE_REQUIRED',
  [ErrorCode.StoreNameRequired]: 'errors.STORE_NAME_REQUIRED',
  [ErrorCode.StoreNameEmpty]: 'errors.STORE_NAME_EMPTY',
  [ErrorCode.StoreCityRequired]: 'errors.STORE_CITY_REQUIRED',
  [ErrorCode.StoreCityEmpty]: 'errors.STORE_CITY_EMPTY',
  [ErrorCode.StoreUrlInvalid]: 'errors.STORE_URL_INVALID',
  [ErrorCode.CompanyIdInvalid]: 'errors.COMPANY_ID_INVALID',
  [ErrorCode.UnsupportedCountry]: 'errors.UNSUPPORTED_COUNTRY',
  [ErrorCode.StoreCountryEditRequiresTrust]: 'errors.STORE_COUNTRY_EDIT_REQUIRES_TRUST',
  [ErrorCode.ProductCreateRequiresLogin]: 'errors.PRODUCT_CREATE_REQUIRES_LOGIN',
  [ErrorCode.ProductEditRequiresLogin]: 'errors.PRODUCT_EDIT_REQUIRES_LOGIN',
  [ErrorCode.StoreCreateRequiresLogin]: 'errors.STORE_CREATE_REQUIRES_LOGIN',
  [ErrorCode.StoreEditRequiresLogin]: 'errors.STORE_EDIT_REQUIRES_LOGIN',
  [ErrorCode.DuplicateProductCode]: 'errors.DUPLICATE_PRODUCT_CODE',
  [ErrorCode.DuplicateGenericProduct]: 'errors.DUPLICATE_GENERIC_PRODUCT',
  [ErrorCode.DuplicateStore]: 'errors.DUPLICATE_STORE',
  [ErrorCode.PhotoUploadRequiresLogin]: 'errors.PHOTO_UPLOAD_REQUIRES_LOGIN',
  [ErrorCode.PhotoActionRequiresLogin]: 'errors.PHOTO_ACTION_REQUIRES_LOGIN',
  [ErrorCode.PhotoCannotAttachToPhoto]: 'errors.PHOTO_CANNOT_ATTACH_TO_PHOTO',
  [ErrorCode.PhotoCannotAttachToUser]: 'errors.PHOTO_CANNOT_ATTACH_TO_USER',
  [ErrorCode.PhotoLimitReached]: 'errors.PHOTO_LIMIT_REACHED',
  [ErrorCode.PhotoDeleteNotOwner]: 'errors.PHOTO_DELETE_NOT_OWNER',
  [ErrorCode.PhotoUpdateNotOwner]: 'errors.PHOTO_UPDATE_NOT_OWNER',
  [ErrorCode.PhotoNotFound]: 'errors.PHOTO_NOT_FOUND',
  [ErrorCode.PhotoTargetRecordNotFound]: 'errors.PHOTO_TARGET_RECORD_NOT_FOUND',
  [ErrorCode.PhotoTooLarge]: 'errors.PHOTO_TOO_LARGE',
  [ErrorCode.PhotoUnsupportedFormat]: 'errors.PHOTO_UNSUPPORTED_FORMAT',
  [ErrorCode.PhotoUnreadable]: 'errors.PHOTO_UNREADABLE',
  [ErrorCode.PhotoResolutionTooHigh]: 'errors.PHOTO_RESOLUTION_TOO_HIGH',
  [ErrorCode.QualityRequiresLogin]: 'errors.QUALITY_REQUIRES_LOGIN',
  [ErrorCode.QualityGradeOutOfRange]: 'errors.QUALITY_GRADE_OUT_OF_RANGE',
  [ErrorCode.FlagRequiresLogin]: 'errors.FLAG_REQUIRES_LOGIN',
  [ErrorCode.FlagRecordNotFound]: 'errors.FLAG_RECORD_NOT_FOUND',
  [ErrorCode.ModerationRequiresRole]: 'errors.MODERATION_REQUIRES_ROLE',
  [ErrorCode.ModerationRecordNotFound]: 'errors.MODERATION_RECORD_NOT_FOUND',
  [ErrorCode.ModerationObservationNotFound]: 'errors.MODERATION_OBSERVATION_NOT_FOUND',
  [ErrorCode.ModerationUserNotFound]: 'errors.MODERATION_USER_NOT_FOUND',
  [ErrorCode.FeedbackMessageRequired]: 'errors.FEEDBACK_MESSAGE_REQUIRED',
  [ErrorCode.FeedbackMessageTooLong]: 'errors.FEEDBACK_MESSAGE_TOO_LONG',
  [ErrorCode.FeedbackContactEmailInvalid]: 'errors.FEEDBACK_CONTACT_EMAIL_INVALID',
  [ErrorCode.FeedbackNotFound]: 'errors.FEEDBACK_NOT_FOUND',
  [ErrorCode.InvalidChallenge]: 'errors.INVALID_CHALLENGE',
  [ErrorCode.SessionExpired]: 'errors.SESSION_EXPIRED',
  [ErrorCode.LocaleRequiresLogin]: 'errors.LOCALE_REQUIRES_LOGIN',
  [ErrorCode.LocaleUnsupported]: 'errors.LOCALE_UNSUPPORTED',
  [ErrorCode.ProfileRequiresLogin]: 'errors.PROFILE_REQUIRES_LOGIN',
  [ErrorCode.ProfileNameTooLong]: 'errors.PROFILE_NAME_TOO_LONG',
  [ErrorCode.ProfileDisplayNameTooLong]: 'errors.PROFILE_DISPLAY_NAME_TOO_LONG',
  [ErrorCode.ProfilePhoneInvalid]: 'errors.PROFILE_PHONE_INVALID',
  [ErrorCode.ProfileEmailInvalid]: 'errors.PROFILE_EMAIL_INVALID',
  [ErrorCode.AvatarRequiresLogin]: 'errors.AVATAR_REQUIRES_LOGIN',
  [ErrorCode.EmailChangeRequiresLogin]: 'errors.EMAIL_CHANGE_REQUIRES_LOGIN',
  [ErrorCode.EmailChangeSameAddress]: 'errors.EMAIL_CHANGE_SAME_ADDRESS',
  [ErrorCode.EmailChangeInvalidChallenge]: 'errors.EMAIL_CHANGE_INVALID_CHALLENGE',
  [ErrorCode.EmailChangeEmailTaken]: 'errors.EMAIL_CHANGE_EMAIL_TAKEN',
  [ErrorCode.AccountGone]: 'errors.ACCOUNT_GONE',
  [ErrorCode.AccountDeleteRequiresLogin]: 'errors.ACCOUNT_DELETE_REQUIRES_LOGIN',
  [ErrorCode.AccountDeleteInvalidChallenge]: 'errors.ACCOUNT_DELETE_INVALID_CHALLENGE',
  [ErrorCode.ContributionsRequireLogin]: 'errors.CONTRIBUTIONS_REQUIRE_LOGIN',
  [ErrorCode.TermsAcceptanceRequired]: 'errors.TERMS_ACCEPTANCE_REQUIRED',
  [ErrorCode.AccountSuspended]: 'errors.ACCOUNT_SUSPENDED',
  [ErrorCode.TooManyRequests]: 'errors.TOO_MANY_REQUESTS',
  [ErrorCode.UomMismatch]: 'errors.UOM_MISMATCH',
  [ErrorCode.ValidationFailed]: 'errors.VALIDATION_FAILED',
  [ErrorCode.ClientVersionTooOld]: 'errors.CLIENT_VERSION_TOO_OLD',
  [ErrorCode.ObservationAlreadySubmittedToday]: 'errors.OBSERVATION_ALREADY_SUBMITTED_TODAY',
  [ErrorCode.ObservationPricesRequired]: 'errors.OBSERVATION_PRICES_REQUIRED',
  [ErrorCode.ObservationDuplicatePriceKind]: 'errors.OBSERVATION_DUPLICATE_PRICE_KIND',
  [ErrorCode.ObservationPriceKindAlreadySubmittedToday]:
    'errors.OBSERVATION_PRICE_KIND_ALREADY_SUBMITTED_TODAY',
  [ErrorCode.ObservationPriceIncomplete]: 'errors.OBSERVATION_PRICE_INCOMPLETE',
  [ErrorCode.ObservationPromoValidityNotAllowed]: 'errors.OBSERVATION_PROMO_VALIDITY_NOT_ALLOWED',
  [ErrorCode.ObservationPromoValidityRangeInvalid]:
    'errors.OBSERVATION_PROMO_VALIDITY_RANGE_INVALID',
  [ErrorCode.ObservationPromoValidFromInFuture]: 'errors.OBSERVATION_PROMO_VALID_FROM_IN_FUTURE',
};

/**
 * Druhy ceny nabízené ve formuláři zápisu — všech pět hodnot `PriceKind`, včetně MULTIBUY
 * (množstevní sleva, `shared/price-entry-form.ts` umí i její pole navíc `multibuyQty`/
 * `multibuyTotal`, docs/datovy-model.md). `availablePriceKinds` (`shared/price-rows.ts`) z týhle
 * množiny navíc vyloučí druhy použité v jiných řádcích jedné dávky.
 */
export const SELECTABLE_PRICE_KINDS: readonly PriceKind[] = [
  PriceKind.Regular,
  PriceKind.Promo,
  PriceKind.ClubCard,
  PriceKind.Clearance,
  PriceKind.Multibuy,
];

/** Druhy uvedení ceny nabízené pro váhové zboží (isVariableWeight) — bez PACKAGE, to je výchozí pro ostatní. */
export const SELECTABLE_VARIABLE_WEIGHT_QUANTITY_BASES: readonly QuantityBasis[] = [
  QuantityBasis.PerKg,
  QuantityBasis.PerL,
  QuantityBasis.PerPiece,
];
