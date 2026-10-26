package cz.kvalitacena.receipt

/**
 * Rozhodování kolem účtenek bez Androidu — co dělat s nepovedeným uploadem, kdy se znovu ptát
 * na souhlas a kdy ukázat notifikaci. Testy v `ReceiptPolicyTest`.
 */

/** Co s účtenkou, jejíž odeslání selhalo. */
enum class UploadFailureAction {
  /** Výpadek sítě nebo serveru — WorkManager to zkusí znovu s rostoucím odstupem. */
  RETRY,

  /** Chybí nový souhlas — účtenka čeká v telefonu, dokud ho uživatel nedá. */
  NEEDS_CONSENT,

  /** Opakovat nemá smysl (chybí přihlášení, denní limit, vadný obrázek) — rozhodne uživatel. */
  FAIL,
}

const val CODE_CONSENT_REQUIRED = "RECEIPT_CONSENT_REQUIRED"
const val CODE_REQUIRES_LOGIN = "RECEIPT_REQUIRES_LOGIN"

/** Co s rozpracovaným konceptem, když ho otevře účet [currentUid] ([draftClaim]). */
enum class DraftClaim {
  /** Koncept patří přihlášenému účtu, nebo appka zatím neví, kdo je přihlášený. */
  KEEP,

  /** Koncept zatím nikomu nepatří (prázdný, nebo z verze appky před vlastníkem) — převezme ho přihlášený. */
  ADOPT,

  /** Rozpracované díly jiného účtu — zahodit, ať je nikdo jiný neuvidí ani neodešle pod sebou. */
  DISCARD,
}

/**
 * Komu patří rozpracovaný koncept účtenky (docs/soukromi.md, „Účtenky"). Vlastník fronty se
 * určuje až při zařazení ([PendingReceiptStore.commitDraft]), takže bez téhle kontroly by
 * nafocenou účtenku jednoho účtu po přepnutí účtu odeslal ten druhý jako svou.
 * Neznámý přihlášený účet ([currentUid] `null` — přihlášení ze starší verze, dokud obnova
 * tokenu uid neuloží) koncept nechá být: zahodit cizí nejde poznat, zahodit vlastní by byla ztráta.
 */
fun draftClaim(draftOwner: String?, hasParts: Boolean, currentUid: String?): DraftClaim = when {
  currentUid == null || draftOwner == currentUid -> DraftClaim.KEEP
  draftOwner == null || !hasParts -> DraftClaim.ADOPT
  else -> DraftClaim.DISCARD
}

/**
 * [status] je HTTP status odpovědi, 0 = odpověď vůbec nepřišla (bez signálu v obchodě — přesně
 * případ, kvůli kterému se účtenka odesílá přes WorkManager). Denní limit (`RECEIPT_DAILY_LIMIT`,
 * 429) se neopakuje automaticky: zítra by prošel, ale do té doby by worker jen pálil baterii.
 */
fun uploadFailureAction(status: Int, code: String?): UploadFailureAction = when {
  code == CODE_CONSENT_REQUIRED -> UploadFailureAction.NEEDS_CONSENT
  code == "RECEIPT_DAILY_LIMIT" -> UploadFailureAction.FAIL
  status == 0 || status == 408 || status == 429 || status >= 500 -> UploadFailureAction.RETRY
  else -> UploadFailureAction.FAIL
}

/**
 * Verze textu souhlasu, který appka SAMA ukazuje (`receipt_consent_*` ve strings.xml). Ukládá
 * a posílá se tahle, ne číslo ze serveru — jinak by stará appka po zvednutí
 * `app.receipt.consent-version` odeslala souhlas s textem, který uživatel neviděl
 * (docs/soukromi.md, „Účtenky"). Při změně textu zvednout spolu se serverem.
 */
const val RECEIPT_CONSENT_VERSION = 1

/** Server chce jiné znění souhlasu, než jaké tahle verze appky umí ukázat — pomůže jen aktualizace. */
fun consentOutdated(serverVersion: Int): Boolean = serverVersion != RECEIPT_CONSENT_VERSION

/**
 * Ptát se znovu na souhlas? Při prvním odeslání ([acceptedVersion] 0) vždy, pak jen když server
 * zvedl verzi textu (`receiptSummary.consentVersion`, docs/soukromi.md „Účtenky" — změna
 * zpracovatele znamená nové zaškrtnutí, ne tichou výměnu).
 */
fun consentRequired(acceptedVersion: Int, serverVersion: Int): Boolean =
  acceptedVersion <= 0 || acceptedVersion < serverVersion

/**
 * Ukázat notifikaci „účtenka zpracovaná"? Jen při NÁRŮSTU neviděných proti poslední
 * notifikaci — stejný počet po třech hodinách je stará zpráva, ne nová.
 */
fun shouldNotify(lastNotifiedUnseen: Int, unseenCount: Int): Boolean = unseenCount > lastNotifiedUnseen

/** Trasa, kterou otevře ťuknutí na notifikaci: jediná nová účtenka rovnou detail, jinak seznam. */
fun notificationRoute(unseenIds: List<String>): String =
  unseenIds.singleOrNull()?.let { "receipt/$it" } ?: "receipts"

/** Trasa z notifikace smí být jen seznam nebo detail účtenky — intent je zvenku appky. */
fun isAllowedNotificationRoute(route: String): Boolean =
  route == "receipts" || Regex("^receipt/\\d+$").matches(route)
