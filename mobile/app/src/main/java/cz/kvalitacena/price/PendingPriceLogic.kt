package cz.kvalitacena.price

import cz.kvalitacena.network.ClientUpdateRequiredException
import cz.kvalitacena.network.GraphQlAppException
import cz.kvalitacena.network.ObservationPriceInput
import cz.kvalitacena.network.TransportException
import java.io.IOException
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Zboží s tímhle kódem katalog nezná (ani po připojení) — uživatel ho musí založit nebo vybrat. */
const val REASON_PRODUCT_UNKNOWN = "PRODUCT_UNKNOWN"

/** Zboží se dohledalo, ale je váhové a cena byla zapsaná naslepo jako „za balení". */
const val REASON_VARIABLE_WEIGHT = "VARIABLE_WEIGHT"

/** Cenu zapsal přihlášený uživatel a teď není přihlášený nikdo, nebo někdo jiný. */
const val CODE_PRICE_REQUIRES_LOGIN = "PRICE_REQUIRES_LOGIN"

/** Server odmítá tuhle verzi appky (HTTP 426) — pomůže jen aktualizace. */
const val CODE_CLIENT_UPDATE_REQUIRED = "CLIENT_UPDATE_REQUIRED"

/**
 * Má se neúspěšné odeslání z obrazovky zařadit do fronty, místo aby se ukázalo jako chyba?
 * Jen když selhala SÍŤ: [IOException] je spojení, které se nenavázalo nebo spadlo, a
 * [TransportException] odpověď mimo GraphQL kontrakt (výpadek serveru, brána). Odpověď serveru
 * s chybou ([GraphQlAppException]) je naopak odpověď — tu musí uživatel vidět hned a opravit.
 */
fun shouldQueueAfterFailure(error: Throwable): Boolean =
  error !is ClientUpdateRequiredException && (error is IOException || error is TransportException)

/** Co worker udělá po dohledání zboží podle kódu. */
sealed interface LookupDecision {
  /** Zboží je v katalogu a cena může odejít. */
  data class Submit(val productId: String) : LookupDecision

  /** Bez uživatele to nejde — [reason] je [REASON_PRODUCT_UNKNOWN] nebo [REASON_VARIABLE_WEIGHT]. */
  data class NeedsProduct(val reason: String, val productId: String? = null) : LookupDecision

  /** Odpověď není konečná (Open Food Facts jsou dočasně nedostupné) — zkusit později. */
  data object Retry : LookupDecision
}

/**
 * [status] je `ProductLookupResult.status`. Zboží, které zná jen Open Food Facts
 * (`OFF_CANDIDATE`), se NEZAKLÁDÁ samo: smí vzniknout výhradně přes `createProductFromOff` po
 * potvrzení uživatelem (kořenový CLAUDE.md, ODbL) — worker ho jen označí k doplnění.
 * `OFF_UNAVAILABLE` není „neznáme": katalog ho nemá, ale OFF se zeptat nešlo, takže by formulář
 * nového zboží přišel o předvyplnění; počká se.
 */
fun lookupDecision(
  status: String,
  productId: String?,
  isVariableWeight: Boolean,
  basisChosenByUser: Boolean,
): LookupDecision = when {
  status == "EXISTING" && productId != null ->
    if (isVariableWeight && !basisChosenByUser) LookupDecision.NeedsProduct(REASON_VARIABLE_WEIGHT, productId)
    else LookupDecision.Submit(productId)
  status == "OFF_UNAVAILABLE" -> LookupDecision.Retry
  else -> LookupDecision.NeedsProduct(REASON_PRODUCT_UNKNOWN)
}

enum class SubmitFailureAction {
  /** Síť nebo server zakolísaly; WorkManager to zkusí znovu. */
  RETRY,

  /** Opakování nepomůže, uživatel musí vidět proč. */
  FAILED,
}

/** Server už má od tohoto uživatele cenu druhu `params[0]` z téhož dne — odmítl CELOU dávku. */
const val CODE_PRICE_KIND_ALREADY_SUBMITTED_TODAY = "OBSERVATION_PRICE_KIND_ALREADY_SUBMITTED_TODAY"

/**
 * Chyba při odesílání z fronty, kterou nevyřešilo [pricesAfterKindCollision].
 * `OBSERVATION_ALREADY_SUBMITTED_TODAY` hlásí server jen při závodu dvou souběžných zápisů
 * (unikátní index bez jména druhu) — příští pokus už kolizi pojmenuje, proto [RETRY].
 * Opakování téže dávky po ztracené odpovědi sem nepatří: to server pozná podle id odeslání
 * a vrátí úspěch.
 */
fun submitFailureAction(error: Throwable): SubmitFailureAction = when {
  error is GraphQlAppException && error.code == "OBSERVATION_ALREADY_SUBMITTED_TODAY" -> SubmitFailureAction.RETRY
  error is GraphQlAppException -> SubmitFailureAction.FAILED
  error is ClientUpdateRequiredException -> SubmitFailureAction.FAILED
  shouldQueueAfterFailure(error) -> SubmitFailureAction.RETRY
  else -> SubmitFailureAction.FAILED
}

/**
 * Server odmítl celou dávku, protože cenu jednoho druhu z téhož dne už má (typicky ji uživatel
 * mezitím zapsal znovu ručně). Vrací ceny, které se mají poslat znovu — bez kolizního druhu;
 * prázdný seznam = nic dalšího k odeslání, položka je vyřízená. `null`, když chyba kolizí není,
 * nebo ji vyřazením řádku vyřešit nejde (druh v dávce není) — pak rozhoduje [submitFailureAction].
 *
 * Dřív se taková odpověď brala jako úspěch celé položky, takže REGULAR se tiše ztratila, když
 * server měl CLUB_CARD.
 */
fun pricesAfterKindCollision(error: Throwable, prices: List<ObservationPriceInput>): List<ObservationPriceInput>? {
  if (error !is GraphQlAppException || error.code != CODE_PRICE_KIND_ALREADY_SUBMITTED_TODAY) return null
  val kind = (error.params.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: return null
  val remaining = prices.filterNot { it.priceKind == kind }
  return remaining.takeIf { it.size < prices.size }
}

enum class OwnerCheck {
  OK,

  /** Přihlášení se teprve obnovuje (uid se uloží s první obnovou tokenu) — zkusit za chvíli. */
  WAIT,

  /** Vlastník není přihlášený — cena jednoho člověka nesmí odejít pod účtem druhého ani anonymně. */
  REQUIRES_LOGIN,
}

/** Smí cena zapsaná účtem [ownerUid] odejít v současném stavu přihlášení? */
fun ownerCheck(ownerUid: String?, loggedIn: Boolean, currentUid: String?): OwnerCheck = when {
  // Zapsáno bez přihlášení: odejde, ať je přihlášený kdokoli nebo nikdo — nikomu ji nebere.
  ownerUid == null -> OwnerCheck.OK
  !loggedIn -> OwnerCheck.REQUIRES_LOGIN
  currentUid == null -> OwnerCheck.WAIT
  currentUid == ownerUid -> OwnerCheck.OK
  else -> OwnerCheck.REQUIRES_LOGIN
}

/**
 * Cena odložená kvůli přihlášení ([CODE_PRICE_REQUIRES_LOGIN]), jejíž vlastník je teď zase
 * přihlášený — má se sama vrátit mezi čekající. Bez toho by po přihlášení ležela ve frontě
 * jako selhaná, dokud by uživatel sám neklepl na „Zkusit znovu".
 */
fun revivesAfterLogin(price: PendingPrice, loggedIn: Boolean, currentUid: String?): Boolean =
  price.state == PendingPriceState.FAILED && price.errorCode == CODE_PRICE_REQUIRES_LOGIN &&
    ownerCheck(price.ownerUid, loggedIn, currentUid) == OwnerCheck.OK

/**
 * Čas pozorování pro frontu: [chosen] je to, co by šlo serveru při přímém odeslání
 * (`toObservedAtIso` — `null` znamená „teď"). Fronta `null` uložit nesmí, viz [PendingPrice].
 */
fun observedAtForQueue(chosen: String?, now: OffsetDateTime = OffsetDateTime.now()): String = chosen ?: now.toString()

/**
 * Podklady pro zařazení — jedno místo, kde z formuláře vzniká [PendingPrice]. [submissionId] je
 * id odeslání, se kterým formulář cenu už jednou zkusil poslat; stane se z něj id položky fronty
 * a worker ho pošle jako `clientSubmissionId`. Když první pokus na serveru prošel a ztratila se
 * jen odpověď, server opakování pozná a cenu nezapíše podruhé.
 */
fun pendingPriceDraft(
  submissionId: String,
  ownerUid: String?,
  productId: String?,
  barcode: String?,
  productName: String?,
  storeId: String,
  storeLabel: String,
  currency: String?,
  productAlias: String?,
  quantityBasis: String,
  observedAt: String?,
  prices: List<ObservationPriceInput>,
  now: OffsetDateTime = OffsetDateTime.now(),
): PendingPrice {
  require(productId != null || barcode != null) { "Cena ve frontě musí mít zboží nebo aspoň kód" }
  return PendingPrice(
    id = submissionId,
    ownerUid = ownerUid,
    productId = productId,
    barcode = barcode,
    productName = productName,
    storeId = storeId,
    storeLabel = storeLabel,
    currency = currency,
    productAlias = productAlias,
    // Bez načteného zboží appka neví, že je váhové — uloží PACKAGE a rozhodnutí odloží.
    quantityBasis = if (productId != null) quantityBasis else "PACKAGE",
    basisChosenByUser = productId != null,
    observedAt = observedAtForQueue(observedAt, now),
    prices = prices,
    queuedAt = now.toString(),
  )
}
