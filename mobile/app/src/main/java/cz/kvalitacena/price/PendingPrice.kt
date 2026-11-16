package cz.kvalitacena.price

import cz.kvalitacena.network.ObservationPriceInput
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Stav ceny ve frontě — viz [PendingPrice]. */
@Serializable
enum class PendingPriceState {
  /** Čeká na připojení; [PriceUploadWorker] ji pošle sám. */
  WAITING,

  /** Bez zásahu uživatele odeslat nejde — [PendingPrice.errorCode] říká, co chybí. */
  NEEDS_PRODUCT,

  /** Server ji odmítl natrvalo, nebo ji nejde poslat pod tímhle účtem. */
  FAILED,
}

/**
 * Cena zapsaná bez signálu, čekající na odeslání (docs/design.md, čtvrtý stav obrazovky — zápis
 * v obchodě bez signálu je hlavní scénář appky, ne okrajový případ).
 *
 * Zboží je určené buď [productId] (stihlo se načíst, spadlo až odeslání), nebo jen [barcode]
 * (sken úplně bez signálu — zboží dohledá až worker). [productName] a [storeLabel] jsou jen pro
 * zobrazení ve frontě; server je nedostane.
 *
 * [observedAt] je VŽDY vyplněný čas, kdy uživatel cenu viděl — u běžného zápisu ho appka
 * neposílá a server dosadí „teď", jenže z fronty by „teď" znamenalo až okamžik odeslání
 * (docs/datovy-model.md, `observed_at` ≠ `created_at`). Zároveň je to klíč idempotence: server
 * drží jeden záznam na uživatele, zboží, obchod, druh ceny a DEN pozorování, takže opakované
 * odeslání po ztracené odpovědi cenu nezdvojí ([submitFailureAction]).
 *
 * [basisChosenByUser] = uživatel vybíral [quantityBasis] se znalostí zboží. U skenu bez signálu
 * appka neví, jestli jde o váhové zboží, a uloží `PACKAGE`; ukáže-li se po dohledání váhové,
 * cena se neodešle, dokud uživatel neřekne, jestli je za kus, kilo nebo litr ([lookupDecision]).
 *
 * [ownerUid] je `publicUid` účtu, který cenu zapsal (`null` = zapsáno bez přihlášení). Pod jiným
 * účtem neodejde.
 */
@Serializable
data class PendingPrice(
  val id: String,
  val ownerUid: String? = null,
  val productId: String? = null,
  val barcode: String? = null,
  val productName: String? = null,
  val storeId: String,
  val storeLabel: String,
  val productAlias: String? = null,
  val quantityBasis: String = "PACKAGE",
  val basisChosenByUser: Boolean = false,
  val observedAt: String,
  val prices: List<ObservationPriceInput>,
  val queuedAt: String,
  val state: PendingPriceState = PendingPriceState.WAITING,
  val errorCode: String? = null,
  val errorMessage: String? = null,
)

/**
 * Fronta cen čekajících na odeslání (`filesDir/prices/pending/<uuid>.json`) — stejný vzor jako
 * `receipt/PendingReceiptStore`: soubor na položku (zápis jedné nepoškodí ostatní), stav jako
 * [StateFlow] pro obrazovky. Bez závislosti na Androidu, aby šla testovat nad dočasným adresářem.
 */
class PendingPriceStore(filesDir: File) {

  private val root = File(filesDir, "prices/pending")
  private val json = Json { ignoreUnknownKeys = true }

  private val _pending = MutableStateFlow(load())
  val pending: StateFlow<List<PendingPrice>> = _pending

  fun get(id: String): PendingPrice? = _pending.value.firstOrNull { it.id == id }

  /** Zařadí cenu; `id` přidělí tady. */
  @Synchronized
  fun enqueue(draft: PendingPrice): PendingPrice {
    val price = draft.copy(id = UUID.randomUUID().toString())
    write(price)
    refresh()
    return price
  }

  /** Zboží dohledáno (workerem podle kódu, nebo uživatelem přes formulář) — může se odeslat. */
  @Synchronized
  fun resolveProduct(id: String, productId: String, productName: String?) {
    update(id) {
      it.copy(
        productId = productId,
        productName = productName ?: it.productName,
        state = PendingPriceState.WAITING,
        errorCode = null,
        errorMessage = null,
      )
    }
  }

  /** Uživatel u váhového zboží doplnil, za co cena je — viz [REASON_VARIABLE_WEIGHT]. */
  @Synchronized
  fun chooseBasis(id: String, quantityBasis: String) {
    update(id) {
      it.copy(
        quantityBasis = quantityBasis,
        basisChosenByUser = true,
        state = PendingPriceState.WAITING,
        errorCode = null,
        errorMessage = null,
      )
    }
  }

  @Synchronized
  fun markNeedsProduct(id: String, reason: String, productId: String? = null, productName: String? = null) {
    update(id) {
      it.copy(
        state = PendingPriceState.NEEDS_PRODUCT,
        errorCode = reason,
        errorMessage = null,
        productId = productId ?: it.productId,
        productName = productName ?: it.productName,
      )
    }
  }

  @Synchronized
  fun markFailed(id: String, code: String?, message: String?) {
    update(id) { it.copy(state = PendingPriceState.FAILED, errorCode = code, errorMessage = message) }
  }

  /**
   * „Zkusit znovu" — chyba se zapomene. Položka bez dohledaného zboží se vrací mezi čekající jen
   * tehdy, když jí zboží nechybí kvůli [REASON_VARIABLE_WEIGHT] (tam pomůže jen [chooseBasis]).
   */
  @Synchronized
  fun retry(id: String) {
    update(id) {
      if (it.errorCode == REASON_VARIABLE_WEIGHT) it
      else it.copy(state = PendingPriceState.WAITING, errorCode = null, errorMessage = null)
    }
  }

  @Synchronized
  fun delete(id: String) {
    File(root, "$id.json").delete()
    refresh()
  }

  fun refresh() {
    _pending.value = load()
  }

  private fun update(id: String, transform: (PendingPrice) -> PendingPrice) {
    // Z disku, ne z paměti: worker může běžet v jiné instanci procesu než obrazovka.
    val current = read(File(root, "$id.json")) ?: return
    write(transform(current))
    refresh()
  }

  private fun write(price: PendingPrice) {
    root.mkdirs()
    // Dočasný soubor + přejmenování: pád uprostřed zápisu nesmí nechat useknutý JSON, který by
    // se při příštím načtení tiše zahodil i se zapsanou cenou.
    val target = File(root, "${price.id}.json")
    val tmp = File(root, "${price.id}.json.tmp")
    tmp.writeText(json.encodeToString(PendingPrice.serializer(), price))
    if (!tmp.renameTo(target)) {
      target.writeText(tmp.readText())
      tmp.delete()
    }
  }

  private fun read(file: File): PendingPrice? =
    runCatching { json.decodeFromString(PendingPrice.serializer(), file.readText()) }.getOrNull()

  private fun load(): List<PendingPrice> =
    root.listFiles { file -> file.isFile && file.name.endsWith(".json") }
      ?.mapNotNull { read(it) }
      ?.sortedBy { it.queuedAt }
      .orEmpty()
}
