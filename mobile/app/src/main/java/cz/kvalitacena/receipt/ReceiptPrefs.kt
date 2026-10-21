package cz.kvalitacena.receipt

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

private const val PREFS_NAME = "kvalita_a_cena_receipts"
private const val KEY_CONSENT_VERSION = "consent_version"
private const val KEY_ARCHIVE_CONSENT = "archive_consent"
private const val KEY_SERVER_CONSENT_VERSION = "server_consent_version"
private const val KEY_RECEIPTS_USED = "receipts_used"
private const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
private const val KEY_LAST_NOTIFIED_UNSEEN = "last_notified_unseen"

/**
 * Lokální stav účtenek (docs/soukromi.md, „Účtenky"): odsouhlasená verze textu a volba
 * archivace, poslední známá verze ze serveru, jestli uživatel účtenky vůbec používá (jen pak
 * běží periodická kontrola) a na kolik neviděných účtenek už notifikace upozornila.
 * Nic z toho není citlivé — obsah nákupu se sem neukládá.
 */
class ReceiptPrefs(context: Context) {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  val acceptedConsentVersion: Int get() = prefs.getInt(KEY_CONSENT_VERSION, 0)

  val archiveConsent: Boolean get() = prefs.getBoolean(KEY_ARCHIVE_CONSENT, false)

  var serverConsentVersion: Int
    // Dokud se appka serveru nezeptá, platí znění, které sama ukazuje — souhlas jde dát
    // i offline v obchodě, bez dotazu na server.
    get() = prefs.getInt(KEY_SERVER_CONSENT_VERSION, RECEIPT_CONSENT_VERSION)
    set(value) = prefs.edit { putInt(KEY_SERVER_CONSENT_VERSION, value) }

  /** Poslední známá verze ze serveru je jiná, než jakou appka umí ukázat (consentOutdated). */
  val consentOutdated: Boolean get() = consentOutdated(serverConsentVersion)

  fun needsConsent(): Boolean = consentRequired(acceptedConsentVersion, RECEIPT_CONSENT_VERSION)

  fun acceptConsent(version: Int, archive: Boolean) {
    prefs.edit {
      putInt(KEY_CONSENT_VERSION, version)
      putBoolean(KEY_ARCHIVE_CONSENT, archive)
    }
  }

  var receiptsUsed: Boolean
    get() = prefs.getBoolean(KEY_RECEIPTS_USED, false)
    set(value) = prefs.edit { putBoolean(KEY_RECEIPTS_USED, value) }

  var notificationPermissionAsked: Boolean
    get() = prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)
    set(value) = prefs.edit { putBoolean(KEY_NOTIFICATION_ASKED, value) }

  var lastNotifiedUnseen: Int
    get() = prefs.getInt(KEY_LAST_NOTIFIED_UNSEEN, 0)
    set(value) = prefs.edit { putInt(KEY_LAST_NOTIFIED_UNSEEN, value) }
}
