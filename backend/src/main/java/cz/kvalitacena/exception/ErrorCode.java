package cz.kvalitacena.exception;

import lombok.Getter;

/**
 * Strojově čitelný kód doménové chyby — jde do GraphQL {@code extensions.code} i do REST
 * {@code ProblemDetail.properties.code}. {@link #messageKey} ukazuje do
 * {@code messages/errors.properties} (a jazykových variant), odkud se dotahuje lokalizovaný
 * text pro pole {@code message} (ten je jen fallback, viz {@link AppException}).
 *
 * <p>Stejné hodnoty nese i {@code enum ErrorCode} ve {@code schema.graphqls} — synchronizaci
 * hlídá backendový test (viz plán, fáze 4.1). Frontend si nad ním díky tomu postaví vlastní
 * {@code Record<ErrorCode, string>} stejným trikem jako {@code PRICE_KIND_LABELS}.
 */
@Getter
public enum ErrorCode {

  // --- katalog: nenalezeno
  PRODUCT_NOT_FOUND("error.product.notFound"),
  STORE_NOT_FOUND("error.store.notFound"),
  CATEGORY_NOT_FOUND("error.category.notFound"),
  CHAIN_NOT_FOUND("error.chain.notFound"),

  // --- katalog: validace založení/úpravy zboží
  PRODUCT_NAME_REQUIRED("error.product.nameRequired"),
  PRODUCT_NAME_EMPTY("error.product.nameEmpty"),
  PRODUCT_CATEGORY_REQUIRED("error.product.categoryRequired"),
  PRODUCT_UNIT_BASE_REQUIRED("error.product.unitBaseRequired"),

  // --- katalog: validace založení/úpravy obchodu
  STORE_NAME_REQUIRED("error.store.nameRequired"),
  STORE_NAME_EMPTY("error.store.nameEmpty"),
  STORE_CITY_REQUIRED("error.store.cityRequired"),
  STORE_CITY_EMPTY("error.store.cityEmpty"),
  COMPANY_ID_INVALID("error.store.companyIdInvalid"),
  UNSUPPORTED_COUNTRY("error.store.unsupportedCountry"),
  // Country má na rozdíl od zbytku updateStore tvrdý dopad na měnu/IČO pro VŠECHNY uživatele
  // (docs/lokalizace.md, "Country selector v UI") — proto zapisuje přímo do core.store a
  // vyžaduje TrustLevelService.isTrusted, ne obyčejné přihlášení jako ostatní pole.
  STORE_COUNTRY_EDIT_REQUIRES_TRUST("error.store.countryEditRequiresTrust"),

  // --- katalog: přihlášení vyžadováno
  PRODUCT_CREATE_REQUIRES_LOGIN("error.product.createRequiresLogin"),
  PRODUCT_EDIT_REQUIRES_LOGIN("error.product.editRequiresLogin"),
  STORE_CREATE_REQUIRES_LOGIN("error.store.createRequiresLogin"),
  STORE_EDIT_REQUIRES_LOGIN("error.store.editRequiresLogin"),

  // --- katalog: duplicity
  DUPLICATE_PRODUCT_CODE("error.duplicate.productCode"),
  DUPLICATE_GENERIC_PRODUCT("error.duplicate.genericProduct"),
  DUPLICATE_STORE("error.duplicate.store"),

  // --- fotky
  PHOTO_UPLOAD_REQUIRES_LOGIN("error.photo.uploadRequiresLogin"),
  PHOTO_ACTION_REQUIRES_LOGIN("error.photo.actionRequiresLogin"),
  PHOTO_CANNOT_ATTACH_TO_PHOTO("error.photo.cannotAttachToPhoto"),
  // Avatar profilu jde jen přes dedikovaný POST /api/media/user/avatar (MediaService.uploadAvatar) —
  // obecný upload nesmí umožnit nahrát fotku pod cizí user_id.
  PHOTO_CANNOT_ATTACH_TO_USER("error.photo.cannotAttachToUser"),
  PHOTO_LIMIT_REACHED("error.photo.limitReached"),          // {0} = max fotek na záznam
  PHOTO_DELETE_NOT_OWNER("error.photo.deleteNotOwner"),
  PHOTO_UPDATE_NOT_OWNER("error.photo.updateNotOwner"),
  PHOTO_NOT_FOUND("error.photo.notFound"),
  PHOTO_TARGET_RECORD_NOT_FOUND("error.photo.targetRecordNotFound"),
  PHOTO_TOO_LARGE("error.photo.tooLarge"),
  PHOTO_UNSUPPORTED_FORMAT("error.photo.unsupportedFormat"),
  PHOTO_UNREADABLE("error.photo.unreadable"),
  PHOTO_RESOLUTION_TOO_HIGH("error.photo.resolutionTooHigh"),

  // --- kvalita
  QUALITY_REQUIRES_LOGIN("error.quality.requiresLogin"),
  QUALITY_GRADE_OUT_OF_RANGE("error.quality.gradeOutOfRange"),   // {0} = min, {1} = max

  // --- nahlašování
  FLAG_REQUIRES_LOGIN("error.flag.requiresLogin"),
  FLAG_RECORD_NOT_FOUND("error.flag.recordNotFound"),

  // --- auth
  INVALID_CHALLENGE("error.auth.invalidChallenge"),
  SESSION_EXPIRED("error.auth.sessionExpired"),
  TERMS_ACCEPTANCE_REQUIRED("error.auth.termsAcceptanceRequired"),

  // --- preference jazyka/země (docs/lokalizace.md)
  LOCALE_REQUIRES_LOGIN("error.locale.requiresLogin"),
  LOCALE_UNSUPPORTED("error.locale.unsupported"),              // {0} = zadaný locale

  // --- profil uživatele (docs/soukromi.md, "Profil uživatele a viditelnost")
  PROFILE_REQUIRES_LOGIN("error.profile.requiresLogin"),
  PROFILE_NAME_TOO_LONG("error.profile.nameTooLong"),           // {0} = max délka
  PROFILE_DISPLAY_NAME_TOO_LONG("error.profile.displayNameTooLong"), // {0} = max délka
  PROFILE_PHONE_INVALID("error.profile.phoneInvalid"),
  PROFILE_EMAIL_INVALID("error.profile.emailInvalid"),
  AVATAR_REQUIRES_LOGIN("error.profile.avatarRequiresLogin"),

  // --- změna přihlašovacího e-mailu (samostatný OTP tok, docs/soukromi.md)
  EMAIL_CHANGE_REQUIRES_LOGIN("error.emailChange.requiresLogin"),
  EMAIL_CHANGE_SAME_ADDRESS("error.emailChange.sameAddress"),
  EMAIL_CHANGE_INVALID_CHALLENGE("error.emailChange.invalidChallenge"),
  EMAIL_CHANGE_EMAIL_TAKEN("error.emailChange.emailTaken"),

  // --- výmaz účtu, GDPR (docs/soukromi.md, "GDPR")
  ACCOUNT_DELETE_REQUIRES_LOGIN("error.accountDelete.requiresLogin"),
  ACCOUNT_DELETE_INVALID_CHALLENGE("error.accountDelete.invalidChallenge"),

  // --- moje příspěvky (výpis vlastní uživatelské vrstvy, docs/datovy-model.md)
  CONTRIBUTIONS_REQUIRE_LOGIN("error.contributions.requiresLogin"),

  // --- obecné
  ACCOUNT_GONE("error.common.accountGone"),
  TOO_MANY_REQUESTS("error.common.tooManyRequests"),
  UOM_MISMATCH("error.netContent.uomMismatch"),               // {0} = uom, {1} = unitBase
  VALIDATION_FAILED("error.common.validationFailed"),
  CLIENT_VERSION_TOO_OLD("error.common.clientVersionTooOld");

  private final String messageKey;

  ErrorCode(String messageKey) {
    this.messageKey = messageKey;
  }
}
