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
