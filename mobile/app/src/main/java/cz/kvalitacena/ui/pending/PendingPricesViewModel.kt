package cz.kvalitacena.ui.pending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.auth.AuthRepository
import cz.kvalitacena.network.Product
import cz.kvalitacena.price.LookupDecision
import cz.kvalitacena.price.PendingPrice
import cz.kvalitacena.price.PendingPriceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fronta cen zapsaných bez signálu (`price/PendingPriceStore`) a akce nad ní. Odesílání samo
 * dělá `PriceUploadWorker`; tady se jen řeší položky, které bez uživatele odejít nemůžou, a po
 * každé akci, která položku vrátí mezi čekající, se odeslání naplánuje znovu ([scheduleUpload]).
 */
class PendingPricesViewModel(
  private val store: PendingPriceStore,
  private val authRepository: AuthRepository,
  uploadRunning: Flow<Boolean>,
  /** `PriceWork.enqueueUpload` — potřebuje Context, proto zvenčí (stejně jako PriceEntryViewModel). */
  private val scheduleUpload: () -> Unit,
) : ViewModel() {

  val pending: StateFlow<List<PendingPrice>> = store.pending

  /**
   * `publicUid` přihlášeného účtu — cenu jiného účtu obrazovka označí hned, ne až po běhu
   * workeru. Čte Keystore (`AuthRepository.currentUserUid`), proto mimo hlavní vlákno.
   */
  private val _currentUid = MutableStateFlow<String?>(null)
  val currentUid: StateFlow<String?> = _currentUid

  val uploading: StateFlow<Boolean> = uploadRunning.stateIn(viewModelScope, SharingStarted.Eagerly, false)

  /**
   * Položka, pro kterou je otevřený formulář zboží — výsledek přijde přes `NavigationResults`
   * po návratu ([onProductChosen]). Stačí v paměti: ani `NavigationResults` smrt procesu nepřežije.
   */
  private var resolvingId: String? = null

  init {
    // Přihlášení/odhlášení mění, které ceny smí odejít — uid se načte znovu.
    viewModelScope.launch { authRepository.isLoggedIn.collect { refreshUid() } }
    // Doběhlé odesílání: worker píše do téhož úložiště, ale pro jistotu (jiná instance procesu,
    // viz PendingPriceStore.update) se seznam načte z disku znovu.
    viewModelScope.launch { uploading.drop(1).filter { running -> !running }.collect { store.refresh() } }
  }

  private suspend fun refreshUid() {
    _currentUid.value = withContext(Dispatchers.IO) {
      authRepository.currentUserUid().takeIf { authRepository.isLoggedIn.value }
    }
  }

  /** Návrat na obrazovku (z formuláře zboží, z Účtu po přihlášení). */
  fun onShown() {
    store.refresh()
    viewModelScope.launch { refreshUid() }
  }

  fun startCompletingProduct(id: String) {
    resolvingId = id
  }

  /** Zboží založené nebo vybrané ve formuláři pro položku z [startCompletingProduct]. */
  fun onProductChosen(product: Product) {
    val id = resolvingId ?: return
    resolvingId = null
    // Mezitím smazaná (nebo už vyřízená) položka — výsledek formuláře nemá kam patřit.
    val price = store.get(id) ?: return
    when (val decision = decisionAfterProductChosen(price, product.id, product.isVariableWeight)) {
      is LookupDecision.Submit -> {
        store.resolveProduct(id, decision.productId, product.name)
        scheduleUpload()
      }
      // Zboží na váhu: položka zůstane u uživatele, teď už s otázkou, za co cena byla.
      is LookupDecision.NeedsProduct -> store.markNeedsProduct(id, decision.reason, product.id, product.name)
      LookupDecision.Retry -> Unit
    }
  }

  fun chooseBasis(id: String, quantityBasis: String) {
    store.chooseBasis(id, quantityBasis)
    scheduleUpload()
  }

  fun retry(id: String) {
    store.retry(id)
    scheduleUpload()
  }

  fun delete(id: String) {
    store.delete(id)
  }

  /** „Odeslat teď" — `PriceWork` práci nahradí, takže se nečeká na odstup po předchozím selhání. */
  fun sendNow() {
    scheduleUpload()
  }
}
