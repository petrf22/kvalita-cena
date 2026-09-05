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
  // Vícejazyčný název (docs/lokalizace.md) — jazyk mimo app.i18n.supported-locales by appka
  // nikdy nikomu neukázala, takže se odmítne při zápisu, ne až při čtení.
  PRODUCT_NAME_LANG_UNSUPPORTED("error.product.nameLangUnsupported"),
  PRODUCT_NAME_LANG_DUPLICATE("error.product.nameLangDuplicate"),
  // Osobní patch nese jeden jazyk (core.product_user_edit.name_lang) — přepsat cizí názvy
  // ve dvou jazycích naráz nejde, doplnit chybějící libovolně mnoho ano.
  PRODUCT_NAME_EDIT_SINGLE_LANG("error.product.nameEditSingleLang"),
  PRODUCT_CATEGORY_REQUIRED("error.product.categoryRequired"),
  PRODUCT_UNIT_BASE_REQUIRED("error.product.unitBaseRequired"),
  PRODUCT_STORE_REQUIRED("error.product.storeRequired"),
  PRODUCT_TOO_MANY_UNCONFIRMED("error.product.tooManyUnconfirmed"),
  PRODUCT_NOT_AVAILABLE_AT_STORE("error.product.notAvailableAtStore"),
  OFF_PRODUCT_NOT_FOUND("error.off.productNotFound"),
  OFF_UNAVAILABLE("error.off.unavailable"),

  // --- katalog: validace založení/úpravy obchodu
  STORE_NAME_REQUIRED("error.store.nameRequired"),
  STORE_NAME_EMPTY("error.store.nameEmpty"),
  STORE_CITY_REQUIRED("error.store.cityRequired"),
  STORE_CITY_EMPTY("error.store.cityEmpty"),
  STORE_URL_INVALID("error.store.urlInvalid"),
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

  // --- cenové observace
  // uq_price_observation_submitter_kind_per_day — 1 záznam/uživatel/produkt/obchod/druh ceny/den
  // (hrubá síla na spamování nefunguje, docs/reputace.md), viz PriceObservationService.submit().
  // Používá se výhradně jako fallback ze závodu dvou souběžných requestů (DataIntegrityViolation-
  // Exception) — druh ceny tam už nejde zjistit, transakce je abortovaná. Předem zjistitelnou
  // kolizi hlásí OBSERVATION_PRICE_KIND_ALREADY_SUBMITTED_TODAY, s druhem ceny v {0}.
  OBSERVATION_ALREADY_SUBMITTED_TODAY("error.observation.alreadySubmittedToday"),
  OBSERVATION_PRICES_REQUIRED("error.observation.pricesRequired"),
  // {0} = PriceKind — duplicita uvnitř jedné dávky, uživatel má opravit formulář hned.
  OBSERVATION_DUPLICATE_PRICE_KIND("error.observation.duplicatePriceKind"),
  // {0} = PriceKind — tenhle druh ceny už dnes u tohohle zboží/obchodu zapsal, má přijít zítra.
  OBSERVATION_PRICE_KIND_ALREADY_SUBMITTED_TODAY("error.observation.priceKindAlreadySubmittedToday"),
  // {0} = PriceKind — chybí částka (nebo u MULTIBUY qty/total).
  OBSERVATION_PRICE_INCOMPLETE("error.observation.priceIncomplete"),
  // {0} = PriceKind — platnost akce smí mít jen PROMO.
  OBSERVATION_PROMO_VALIDITY_NOT_ALLOWED("error.observation.promoValidityNotAllowed"),
  OBSERVATION_PROMO_VALIDITY_RANGE_INVALID("error.observation.promoValidityRangeInvalid"),
  OBSERVATION_PROMO_VALID_FROM_IN_FUTURE("error.observation.promoValidFromInFuture"),

  // --- kvalita
  QUALITY_REQUIRES_LOGIN("error.quality.requiresLogin"),
  QUALITY_STARS_OUT_OF_RANGE("error.quality.starsOutOfRange"),   // {0} = min, {1} = max

  // --- recenze (core.product_review, text k hodnocení kvality)
  REVIEW_REQUIRES_LOGIN("error.review.requiresLogin"),
  // Text bez hvězdiček nejde založit — hvězdičky se zadávají přes rateProduct.
  REVIEW_REQUIRES_RATING("error.review.requiresRating"),
  REVIEW_TEXT_EMPTY("error.review.textEmpty"),
  REVIEW_TEXT_TOO_LONG("error.review.textTooLong"),   // {0} = max délka

  // --- nahlašování
  FLAG_REQUIRES_LOGIN("error.flag.requiresLogin"),
  FLAG_RECORD_NOT_FOUND("error.flag.recordNotFound"),

  // --- moderace (etapa 1, docs/reputace.md "Moderace") — jen T4, docs/podminky-uziti.md čl. 6-7
  MODERATION_REQUIRES_ROLE("error.moderation.requiresRole"),
  MODERATION_RECORD_NOT_FOUND("error.moderation.recordNotFound"),
  MODERATION_OBSERVATION_NOT_FOUND("error.moderation.observationNotFound"),
  MODERATION_USER_NOT_FOUND("error.moderation.userNotFound"),
  MODERATION_PRODUCT_MERGE_SAME("error.moderation.productMergeSame"),
  MODERATION_PRODUCT_RENAME_INVALID("error.moderation.productRenameInvalid"),
  MODERATION_PRODUCT_MERGE_INVALID("error.moderation.productMergeInvalid"),
  MODERATION_PRODUCT_MERGE_SCOPE_MISMATCH("error.moderation.productMergeScopeMismatch"),

  // --- zpětná vazba (core.feedback, docs/nasazeni.md "Než pozvat první lidi")
  FEEDBACK_MESSAGE_REQUIRED("error.feedback.messageRequired"),
  FEEDBACK_MESSAGE_TOO_LONG("error.feedback.messageTooLong"),         // {0} = max délka
  FEEDBACK_CONTACT_EMAIL_INVALID("error.feedback.contactEmailInvalid"),
  FEEDBACK_NOT_FOUND("error.feedback.notFound"),
  // Proof-of-work výzva (docs/nasazeni.md, obrana proti spamu) — vlastní dvojice kódů, ne
  // sdílené INVALID_CHALLENGE níž (to je pro OTP login/e-mail/výmaz účtu, jiná věc).
  FEEDBACK_CHALLENGE_REQUIRED("error.feedback.challengeRequired"),
  FEEDBACK_CHALLENGE_INVALID("error.feedback.challengeInvalid"),

  // --- auth
  INVALID_CHALLENGE("error.auth.invalidChallenge"),
  SESSION_EXPIRED("error.auth.sessionExpired"),
  TERMS_ACCEPTANCE_REQUIRED("error.auth.termsAcceptanceRequired"),
  // Pozastavený účet (docs/podminky-uziti.md, "Ukončení a vyloučení") — odmítá se už na OTP,
  // ne až tichým vypadnutím ze SecurityContextu (JwtAuthenticationFilter), jinak by uživatel
  // nevěděl proč se nemůže přihlásit.
  ACCOUNT_SUSPENDED("error.auth.accountSuspended"),

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
