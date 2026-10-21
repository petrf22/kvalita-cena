package cz.kvalitacena.receipt

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cz.kvalitacena.AppContainer
import cz.kvalitacena.crash.AppLog
import cz.kvalitacena.network.HttpAppException
import cz.kvalitacena.network.TransportException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Plánování práce s účtenkami přes WorkManager — bez GMS (docs/soukromi.md, „Účtenky":
 * upozornění jen lokální notifikací, žádný push přes Google).
 */
object ReceiptWork {
  const val TAG_UPLOAD = "receipt-upload"
  private const val STATUS_WORK = "receipt-status"

  /** Jak často se appka ptá na zpracované účtenky — zadání: 3 h, jen se sítí. */
  private const val STATUS_INTERVAL_HOURS = 3L

  fun uploadWorkName(pendingId: String) = "receipt-upload-$pendingId"

  /** Tag, podle kterého seznam účtenek pozná, ke které čekající účtence patří stav práce. */
  private const val PENDING_TAG_PREFIX = "receipt-pending:"

  fun pendingTag(pendingId: String) = PENDING_TAG_PREFIX + pendingId

  fun pendingIdFromTags(tags: Set<String>): String? =
    tags.firstOrNull { it.startsWith(PENDING_TAG_PREFIX) }?.removePrefix(PENDING_TAG_PREFIX)

  /** Zrušení odeslání (smazaná čekající účtenka). */
  fun cancelUpload(context: Context, pendingId: String) {
    WorkManager.getInstance(context).cancelUniqueWork(uploadWorkName(pendingId))
  }

  /**
   * Zařadí odeslání — WorkManager ho spustí, až bude připojení (v obchodě bez signálu se tedy
   * nic neděje a nic neselže), a při výpadku zkouší znovu s rostoucím odstupem.
   */
  fun enqueueUpload(context: Context, pendingId: String) {
    val request = OneTimeWorkRequestBuilder<ReceiptUploadWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
      .setInputData(workDataOf(ReceiptUploadWorker.KEY_PENDING_ID to pendingId))
      .addTag(TAG_UPLOAD)
      .addTag(pendingTag(pendingId))
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(uploadWorkName(pendingId), ExistingWorkPolicy.REPLACE, request)
    schedulePeriodicStatus(context)
  }

  /**
   * Jednorázová kontrola hned, jakmile je síť — periodickou práci WorkManager před termínem
   * nespustí ani vynuceně (`cmd jobscheduler run`), takže notifikace by se jinak nedala
   * ověřit dřív než za 3 h. Volá ji ladicí tlačítko v seznamu účtenek (jen debug build).
   */
  fun checkStatusNow(context: Context) {
    val request = OneTimeWorkRequestBuilder<ReceiptStatusWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork("$STATUS_WORK-now", ExistingWorkPolicy.REPLACE, request)
  }

  /**
   * Periodická kontrola zpracovaných účtenek — plánuje se až u toho, kdo účtenky používá,
   * ostatním by appka budila telefon zbytečně. KEEP: opakované volání interval neresetuje.
   */
  fun schedulePeriodicStatus(context: Context) {
    AppContainer.receiptPrefs.receiptsUsed = true
    val request = PeriodicWorkRequestBuilder<ReceiptStatusWorker>(STATUS_INTERVAL_HOURS, TimeUnit.HOURS)
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(STATUS_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
  }
}

/**
 * Odeslání jedné účtenky z fronty ([PendingReceiptStore]). Po úspěchu adresář smaže; chyby,
 * které nemá smysl opakovat, zapíše k účtence, ať je seznam účtenek ukáže s akcí pro uživatele.
 */
class ReceiptUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    AppContainer.init(applicationContext)
    val id = inputData.getString(KEY_PENDING_ID) ?: return Result.failure()
    val store = AppContainer.pendingReceipts
    store.refresh()
    val pending = store.get(id) ?: return Result.success()
    val parts = store.parts(id)
    if (parts.isEmpty()) {
      store.delete(id)
      return Result.success()
    }

    // Worker může běžet v procesu, kde se appka ještě nespustila — stav přihlášení se zjistí
    // z uloženého refresh tokenu, nikdy z přítomnosti access tokenu (kořenový CLAUDE.md).
    val auth = AppContainer.authRepository
    if (!auth.sessionKnown.value) auth.restoreSession()
    if (!auth.isLoggedIn.value) {
      store.markFailed(id, CODE_REQUIRES_LOGIN, null)
      return Result.failure()
    }
    // Účtenku zařadil jiný účet (odhlášení a přihlášení někoho jiného před odesláním) — nákup
    // jednoho člověka nesmí skončit v účtu druhého. Počká, až se vlastník přihlásí zpátky.
    if (pending.ownerUid != null) {
      val current = auth.currentUserUid()
      if (current == null) return Result.retry() // uid se uloží s první obnovou tokenu
      if (current != pending.ownerUid) {
        store.markFailed(id, CODE_REQUIRES_LOGIN, null)
        return Result.failure()
      }
    }

    return try {
      val receiptId = AppContainer.mediaClient.uploadReceipt(
        parts,
        pending.consentVersion,
        pending.archiveConsent,
        pending.capturedAt,
        clientUploadId = pending.id,
      )
      store.delete(id)
      Result.success(workDataOf(KEY_RECEIPT_ID to receiptId))
    } catch (e: HttpAppException) {
      handleFailure(id, e.status, e.code, e.serverMessage)
    } catch (e: TransportException) {
      handleFailure(id, e.status, null, e.message)
    } catch (e: IOException) {
      // Spojení spadlo uprostřed — typicky odchod z dosahu signálu.
      Result.retry()
    }
  }

  private fun handleFailure(id: String, status: Int, code: String?, message: String?): Result =
    when (uploadFailureAction(status, code)) {
      UploadFailureAction.RETRY -> Result.retry()
      UploadFailureAction.NEEDS_CONSENT, UploadFailureAction.FAIL -> {
        AppLog.e("Účtenku se nepodařilo odeslat: HTTP $status ${code.orEmpty()}")
        AppContainer.pendingReceipts.markFailed(id, code, message)
        Result.failure()
      }
    }

  companion object {
    const val KEY_PENDING_ID = "pendingId"
    const val KEY_RECEIPT_ID = "receiptId"
  }
}

/**
 * Periodický dotaz na `receiptSummary.unseenCount` — při nárůstu ukáže lokální notifikaci.
 * Jen pro přihlášeného; anonym účtenky nemá a dotaz by vrátil chybu.
 */
class ReceiptStatusWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    AppContainer.init(applicationContext)
    val auth = AppContainer.authRepository
    if (!auth.sessionKnown.value) auth.restoreSession()
    if (!auth.isLoggedIn.value) return Result.success()

    val prefs = AppContainer.receiptPrefs
    return try {
      val summary = AppContainer.graphQlClient.receiptSummary()
      prefs.serverConsentVersion = summary.consentVersion
      if (shouldNotify(prefs.lastNotifiedUnseen, summary.unseenCount)) {
        val unseenIds = runCatching {
          AppContainer.graphQlClient.myReceipts(first = 20).items.filter { it.unseen }.map { it.id }
        }.getOrDefault(emptyList())
        ReceiptNotifications.showProcessed(applicationContext, summary.unseenCount, notificationRoute(unseenIds))
      }
      prefs.lastNotifiedUnseen = summary.unseenCount
      Result.success()
    } catch (e: Exception) {
      // Periodická práce se stejně zopakuje za 3 h; retry by jen přidal pokusy navíc.
      AppLog.e("Kontrola účtenek selhala: ${e::class.simpleName}", e)
      Result.success()
    }
  }
}
