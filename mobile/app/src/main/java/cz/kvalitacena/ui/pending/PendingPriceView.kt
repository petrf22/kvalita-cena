package cz.kvalitacena.ui.pending

import cz.kvalitacena.R
import cz.kvalitacena.price.CODE_CLIENT_UPDATE_REQUIRED
import cz.kvalitacena.price.CODE_PRICE_REQUIRES_LOGIN
import cz.kvalitacena.price.LookupDecision
import cz.kvalitacena.price.OwnerCheck
import cz.kvalitacena.price.PendingPrice
import cz.kvalitacena.price.PendingPriceState
import cz.kvalitacena.price.REASON_VARIABLE_WEIGHT
import cz.kvalitacena.price.lookupDecision
import cz.kvalitacena.price.ownerCheck
import cz.kvalitacena.ui.common.UiText

/*
 * Co obrazovka čekajících cen u položky fronty ukáže a nabídne — čistá logika bez Androidu
 * (mobile/CLAUDE.md: obrazovky se netestují, rozhodování vytažené sem ano). Stav přihlášení
 * je VŽDY `AuthRepository.isLoggedIn` a `publicUid` účtu, nikdy přítomnost access tokenu
 * (kořenový CLAUDE.md): po startu procesu token chybí, a přihlášený by tu viděl „přihlas se".
 */

/** Co jde s položkou udělat; pořadí v seznamu je pořadí tlačítek. */
enum class PendingPriceAction {
  /** Zboží s kódem katalog nezná — otevřít formulář zboží s předvyplněným kódem. */
  COMPLETE_PRODUCT,

  /** Zboží na váhu zapsané naslepo — vybrat, jestli cena byla za kg, l, nebo kus. */
  CHOOSE_BASIS,

  /** Jen bez přihlášení: cestu k přihlášení (záložka Účet). */
  LOG_IN,
  RETRY,

  /** Vždy — maže se zapsaná cena, obrazovka se proto ptá. */
  DELETE,
}

/**
 * Jak položku ukázat. [title] je stav jednou větou, [detail] co s tím (nebo zpráva serveru);
 * [needsAttention] = bez uživatele neodejde, zvýrazní se a počítá do odznaku zvlášť.
 */
data class PendingPriceView(
  val title: UiText,
  val detail: UiText?,
  val needsAttention: Boolean,
  val sending: Boolean,
  val actions: List<PendingPriceAction>,
)

/**
 * Cena zapsaná jiným účtem (nebo přihlášeným, který se mezitím odhlásil) neodejde
 * (`PriceUploadWorker`, [ownerCheck]). Worker ji označí FAILED až při běhu, tedy až s připojením —
 * obrazovka to ale řekne hned, ať offline neslibuje „odešle se sama". [OwnerCheck.WAIT] (uid se
 * po přihlášení teprve načítá) není blokace.
 */
private fun blockedByOwner(price: PendingPrice, loggedIn: Boolean, currentUid: String?): Boolean =
  ownerCheck(price.ownerUid, loggedIn, currentUid) == OwnerCheck.REQUIRES_LOGIN

/** Neodejde bez zásahu uživatele — zvýraznit a řadit nahoru. */
fun needsAttention(price: PendingPrice, loggedIn: Boolean, currentUid: String?): Boolean =
  price.state != PendingPriceState.WAITING || blockedByOwner(price, loggedIn, currentUid)

/**
 * [online] = `NetworkMonitor.online`, [uploading] = běží práce `PriceWork` (jedna na celou
 * frontu, takže „odesílá se" platí pro všechny čekající naráz).
 */
fun pendingPriceView(
  price: PendingPrice,
  loggedIn: Boolean,
  currentUid: String?,
  online: Boolean,
  uploading: Boolean,
): PendingPriceView {
  val blocked = blockedByOwner(price, loggedIn, currentUid)
  val loginOrNothing = if (loggedIn) emptyList() else listOf(PendingPriceAction.LOG_IN)
  return when {
    // Cizí účet: stejný text, ať ji worker označil, nebo ještě ne. „Zkusit znovu" tu nic
    // nezmění, nabídne se až po přihlášení správným účtem (další větev).
    blocked ->
      PendingPriceView(
        title = UiText.Res(R.string.pending_price_status_requires_login),
        detail = UiText.Res(R.string.pending_price_status_requires_login_text),
        needsAttention = true,
        sending = false,
        actions = loginOrNothing + PendingPriceAction.DELETE,
      )

    // Worker ji odložil kvůli přihlášení a správný účet je zpátky — sama se znovu nezkusí.
    price.state == PendingPriceState.FAILED && price.errorCode == CODE_PRICE_REQUIRES_LOGIN ->
      PendingPriceView(
        title = UiText.Res(R.string.pending_price_status_login_restored),
        detail = UiText.Res(R.string.pending_price_status_login_restored_text),
        needsAttention = true,
        sending = false,
        actions = listOf(PendingPriceAction.RETRY, PendingPriceAction.DELETE),
      )

    price.state == PendingPriceState.FAILED && price.errorCode == CODE_CLIENT_UPDATE_REQUIRED ->
      PendingPriceView(
        title = UiText.Res(R.string.pending_price_status_update_required),
        detail = UiText.Res(R.string.pending_price_status_update_required_text),
        needsAttention = true,
        sending = false,
        actions = listOf(PendingPriceAction.RETRY, PendingPriceAction.DELETE),
      )

    // Zpráva serveru je podle Accept-Language už lokalizovaná (docs/lokalizace.md) — mobil
    // klientský překlad podle `code` zatím nemá, takže ji ukáže celou a samostatně, ne vlepenou
    // do vlastní věty.
    price.state == PendingPriceState.FAILED ->
      PendingPriceView(
        title = UiText.Res(R.string.pending_price_status_failed),
        detail = price.errorMessage?.takeIf { it.isNotBlank() }?.let { UiText.Raw(it) }
          ?: UiText.Res(R.string.pending_price_status_failed_text),
        needsAttention = true,
        sending = false,
        actions = listOf(PendingPriceAction.RETRY, PendingPriceAction.DELETE),
      )

    price.state == PendingPriceState.NEEDS_PRODUCT && price.errorCode == REASON_VARIABLE_WEIGHT ->
      PendingPriceView(
        title = UiText.Res(R.string.pending_price_status_basis),
        detail = UiText.Res(R.string.pending_price_status_basis_text),
        needsAttention = true,
        sending = false,
        actions = listOf(PendingPriceAction.CHOOSE_BASIS, PendingPriceAction.DELETE),
      )

    // REASON_PRODUCT_UNKNOWN a cokoli neznámého: bez zboží cena neodejde. Zakládá se VÝHRADNĚ
    // ve formuláři (zboží známé jen z Open Food Facts nikdy samo — ODbL, kořenový CLAUDE.md),
    // a to jen přihlášený (ProductCatalogService) — anonymovi se nabídne přihlášení.
    price.state == PendingPriceState.NEEDS_PRODUCT ->
      PendingPriceView(
        title = UiText.Res(R.string.pending_price_status_product_unknown),
        detail = UiText.Res(
          if (loggedIn) R.string.pending_price_status_product_unknown_text
          else R.string.pending_price_status_product_unknown_login,
        ),
        needsAttention = true,
        sending = false,
        actions = (if (loggedIn) listOf(PendingPriceAction.COMPLETE_PRODUCT) else loginOrNothing) +
          PendingPriceAction.DELETE,
      )

    // WAITING — odejde sama. Zboží bez id se dohledá až při odeslání; řekne se to, ať uživatele
    // nepřekvapí, že místo názvu vidí jen kód.
    else -> {
      val sending = online && uploading
      PendingPriceView(
        title = UiText.Res(
          when {
            sending -> R.string.pending_price_status_sending
            online -> R.string.pending_price_status_queued
            else -> R.string.pending_price_status_waiting_network
          },
        ),
        detail = if (price.productId == null) UiText.Res(R.string.pending_price_status_lookup_later) else null,
        needsAttention = false,
        sending = sending,
        actions = listOf(PendingPriceAction.DELETE),
      )
    }
  }
}

/**
 * „Odeslat teď" má smysl jen s připojením, když se zrovna neodesílá a je co poslat (čekající
 * položka, která odejít smí). Bez signálu by tlačítko jen naplánovalo práci, která stejně čeká
 * na síť — to už je naplánované od zápisu.
 */
fun canSendNow(
  prices: List<PendingPrice>,
  loggedIn: Boolean,
  currentUid: String?,
  online: Boolean,
  uploading: Boolean,
): Boolean = online && !uploading && prices.any { !needsAttention(it, loggedIn, currentUid) }

/** Počty pro odznak (sken, Moje příspěvky). */
data class PendingPriceSummary(val waiting: Int, val needsAttention: Int) {
  val isEmpty: Boolean get() = waiting == 0 && needsAttention == 0
}

fun pendingPriceSummary(prices: List<PendingPrice>, loggedIn: Boolean, currentUid: String?): PendingPriceSummary {
  val attention = prices.count { needsAttention(it, loggedIn, currentUid) }
  return PendingPriceSummary(waiting = prices.size - attention, needsAttention = attention)
}

/**
 * Text odznaku; `null` = prázdná fronta, odznak se nekreslí vůbec. Zásah má přednost — kdo má
 * jednu cenu k doplnění a pět čekajících, potřebuje vědět hlavně o té jedné; zbytek uvidí
 * na obrazovce čekajících.
 */
fun pendingPriceBadgeText(summary: PendingPriceSummary): UiText? = when {
  summary.needsAttention > 0 ->
    UiText.Plural(R.plurals.pending_prices_badge_attention, summary.needsAttention, listOf(summary.needsAttention))
  summary.waiting > 0 ->
    UiText.Plural(R.plurals.pending_prices_badge_waiting, summary.waiting, listOf(summary.waiting))
  else -> null
}

/**
 * Uživatel doplnil zboží ve formuláři. U zboží na váhu se cena zapsaná naslepo („za balení")
 * nesmí odeslat, dokud neřekne, za co byla — stejné pravidlo jako u dohledání workerem
 * ([lookupDecision]); formulář mohl právě založit i zboží na váhu.
 */
fun decisionAfterProductChosen(price: PendingPrice, productId: String, isVariableWeight: Boolean): LookupDecision =
  lookupDecision(
    status = "EXISTING",
    productId = productId,
    isVariableWeight = isVariableWeight,
    basisChosenByUser = price.basisChosenByUser,
  )
