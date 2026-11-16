package cz.kvalitacena.price

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cz.kvalitacena.AppContainer
import cz.kvalitacena.crash.AppLog
import cz.kvalitacena.network.GraphQlAppException
import cz.kvalitacena.network.SubmitObservationsInput
import java.util.concurrent.TimeUnit

/**
 * Odesílání cen z fronty ([PendingPriceStore]) přes WorkManager — stejný důvod jako u účtenek
 * (`receipt/ReceiptWork`): práce přežije zavření appky i restart telefonu a spustí se sama, až
 * je připojení, bez GMS.
 */
object PriceWork {
  private const val UPLOAD_WORK = "price-upload"

  /**
   * Jedna společná práce pro celou frontu, ne práce na položku: ceny odcházejí postupně v pořadí
   * zápisu a dvě souběžné práce by si mohly tutéž položku poslat dvakrát. `REPLACE` zároveň
   * znamená „zkus to hned" — po ručním „Zkusit znovu" se nečeká na odstup po předchozím selhání.
   */
  fun enqueueUpload(context: Context) {
    val request = OneTimeWorkRequestBuilder<PriceUploadWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(UPLOAD_WORK, ExistingWorkPolicy.REPLACE, request)
  }

  /** Při startu appky: zbylo-li něco čekajícího (práci mohl systém zahodit), naplánovat znovu. */
  fun enqueueIfPending(context: Context) {
    if (AppContainer.pendingPrices.pending.value.any { it.state == PendingPriceState.WAITING }) {
      enqueueUpload(context)
    }
  }
}

/**
 * Pošle všechny čekající ceny. Položku, kterou nejde odeslat bez uživatele, označí
 * ([PendingPriceState.NEEDS_PRODUCT]/[PendingPriceState.FAILED]) a pokračuje další — jedna
 * vadná cena nesmí zadržet zbytek fronty.
 */
class PriceUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    AppContainer.init(applicationContext)
    val store = AppContainer.pendingPrices
    store.refresh()

    // Worker může běžet v procesu, kde se appka ještě nespustila — stav přihlášení se zjistí
    // z uloženého refresh tokenu, nikdy z přítomnosti access tokenu (kořenový CLAUDE.md).
    val auth = AppContainer.authRepository
    if (!auth.sessionKnown.value) auth.restoreSession()

    var retryLater = false
    for (queued in store.pending.value.filter { it.state == PendingPriceState.WAITING }) {
      // Znovu z disku: uživatel ji mezitím mohl smazat nebo upravit v seznamu čekajících.
      val price = store.get(queued.id)?.takeIf { it.state == PendingPriceState.WAITING } ?: continue
      when (ownerCheck(price.ownerUid, auth.isLoggedIn.value, auth.currentUserUid())) {
        OwnerCheck.OK -> Unit
        OwnerCheck.WAIT -> {
          retryLater = true
          continue
        }
        OwnerCheck.REQUIRES_LOGIN -> {
          store.markFailed(price.id, CODE_PRICE_REQUIRES_LOGIN, null)
          continue
        }
      }
      if (!upload(store, price)) retryLater = true
    }
    return if (retryLater) Result.retry() else Result.success()
  }

  /** Vrací `false`, když má smysl zkusit položku později (síť); jinak je vyřízená nebo označená. */
  private suspend fun upload(store: PendingPriceStore, price: PendingPrice): Boolean {
    val client = AppContainer.graphQlClient
    return try {
      val productId = price.productId ?: run {
        val lookup = client.productLookupByCode(price.barcode.orEmpty())
        when (
          val decision = lookupDecision(
            status = lookup.status,
            productId = lookup.product?.id,
            isVariableWeight = lookup.product?.isVariableWeight == true,
            basisChosenByUser = price.basisChosenByUser,
          )
        ) {
          is LookupDecision.Submit -> {
            // Uložit hned: spadne-li odeslání, příští pokus už zboží nehledá.
            store.resolveProduct(price.id, decision.productId, lookup.product?.name)
            decision.productId
          }
          is LookupDecision.NeedsProduct -> {
            store.markNeedsProduct(price.id, decision.reason, decision.productId, lookup.product?.name)
            return true
          }
          LookupDecision.Retry -> return false
        }
      }
      client.submitObservations(
        SubmitObservationsInput(
          productId = productId,
          storeId = price.storeId,
          productAlias = price.productAlias,
          quantityBasis = price.quantityBasis,
          observedAt = price.observedAt,
          prices = price.prices,
        ),
      )
      store.delete(price.id)
      true
    } catch (e: Exception) {
      when (submitFailureAction(e)) {
        SubmitFailureAction.DONE -> {
          store.delete(price.id)
          true
        }
        SubmitFailureAction.RETRY -> false
        SubmitFailureAction.FAILED -> {
          val code = (e as? GraphQlAppException)?.code
            ?: CODE_CLIENT_UPDATE_REQUIRED.takeIf { e is cz.kvalitacena.network.ClientUpdateRequiredException }
          AppLog.e("Cenu z fronty se nepodařilo odeslat: ${code ?: e::class.simpleName}", e)
          store.markFailed(price.id, code, (e as? GraphQlAppException)?.serverMessage)
          true
        }
      }
    }
  }
}
