package cz.kvalitacena.ui.pending

import cz.kvalitacena.R
import cz.kvalitacena.network.ObservationPriceInput
import cz.kvalitacena.price.CODE_CLIENT_UPDATE_REQUIRED
import cz.kvalitacena.price.CODE_PRICE_REQUIRES_LOGIN
import cz.kvalitacena.price.LookupDecision
import cz.kvalitacena.price.PendingPrice
import cz.kvalitacena.price.PendingPriceState
import cz.kvalitacena.price.REASON_PRODUCT_UNKNOWN
import cz.kvalitacena.price.REASON_VARIABLE_WEIGHT
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.pending.PendingPriceAction.CHOOSE_BASIS
import cz.kvalitacena.ui.pending.PendingPriceAction.COMPLETE_PRODUCT
import cz.kvalitacena.ui.pending.PendingPriceAction.DELETE
import cz.kvalitacena.ui.pending.PendingPriceAction.LOG_IN
import cz.kvalitacena.ui.pending.PendingPriceAction.RETRY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Čistá logika obrazovky čekajících cen — stejný vzor jako PublicationStatusTextTest. */
class PendingPriceViewTest {

  private fun price(
    state: PendingPriceState = PendingPriceState.WAITING,
    errorCode: String? = null,
    errorMessage: String? = null,
    ownerUid: String? = null,
    productId: String? = null,
    basisChosenByUser: Boolean = false,
  ) = PendingPrice(
    id = "id-${state.name}-$errorCode",
    ownerUid = ownerUid,
    productId = productId,
    barcode = "8594001234585",
    storeId = "s1",
    storeLabel = "Albert, Vzorov",
    currency = "CZK",
    basisChosenByUser = basisChosenByUser,
    observedAt = "2026-11-16T10:00:00+01:00",
    prices = listOf(ObservationPriceInput(priceKind = "REGULAR", priceAmount = 29.9)),
    queuedAt = "2026-11-16T10:00:00+01:00",
    state = state,
    errorCode = errorCode,
    errorMessage = errorMessage,
  )

  private fun view(
    price: PendingPrice,
    loggedIn: Boolean = true,
    currentUid: String? = "u1",
    online: Boolean = true,
    uploading: Boolean = false,
  ) = pendingPriceView(price, loggedIn, currentUid, online, uploading)

  @Test
  fun waitingOfflineSaysItGoesOnceConnected() {
    val v = view(price(), online = false)
    assertEquals(UiText.Res(R.string.pending_price_status_waiting_network), v.title)
    // Bez dohledaného zboží se řekne, že se dohledá při odeslání.
    assertEquals(UiText.Res(R.string.pending_price_status_lookup_later), v.detail)
    assertFalse(v.needsAttention)
    assertFalse(v.sending)
    assertEquals(listOf(DELETE), v.actions)
  }

  @Test
  fun waitingWhileWorkerRunsIsSending() {
    val v = view(price(productId = "p1"), online = true, uploading = true)
    assertEquals(UiText.Res(R.string.pending_price_status_sending), v.title)
    assertNull(v.detail)
    assertTrue(v.sending)
  }

  @Test
  fun waitingOnlineWithoutRunningWorkerIsQueued() {
    val v = view(price(productId = "p1"), online = true, uploading = false)
    assertEquals(UiText.Res(R.string.pending_price_status_queued), v.title)
    assertFalse(v.sending)
  }

  @Test
  fun uploadingDoesNotCountAsSendingWhenOffline() {
    // WorkManager může hlásit RUNNING ještě chvíli po ztrátě signálu — offline vyhrává.
    val v = view(price(), online = false, uploading = true)
    assertEquals(UiText.Res(R.string.pending_price_status_waiting_network), v.title)
    assertFalse(v.sending)
  }

  @Test
  fun unknownProductLetsSignedInUserCompleteIt() {
    val v = view(price(PendingPriceState.NEEDS_PRODUCT, REASON_PRODUCT_UNKNOWN))
    assertEquals(UiText.Res(R.string.pending_price_status_product_unknown), v.title)
    assertEquals(UiText.Res(R.string.pending_price_status_product_unknown_text), v.detail)
    assertTrue(v.needsAttention)
    assertEquals(listOf(COMPLETE_PRODUCT, DELETE), v.actions)
  }

  @Test
  fun unknownProductAsksAnonymousUserToSignIn() {
    // Založení zboží vyžaduje přihlášení — formulář by anonymovi skončil UNAUTHORIZED.
    val v = view(price(PendingPriceState.NEEDS_PRODUCT, REASON_PRODUCT_UNKNOWN), loggedIn = false, currentUid = null)
    assertEquals(UiText.Res(R.string.pending_price_status_product_unknown_login), v.detail)
    assertEquals(listOf(LOG_IN, DELETE), v.actions)
  }

  @Test
  fun variableWeightAsksForBasisAndNeverOffersPlainRetry() {
    val v = view(price(PendingPriceState.NEEDS_PRODUCT, REASON_VARIABLE_WEIGHT, productId = "p1"))
    assertEquals(UiText.Res(R.string.pending_price_status_basis), v.title)
    assertTrue(v.needsAttention)
    // PendingPriceStore.retry ji stejně nechá být — pomůže jen volba základu.
    assertEquals(listOf(CHOOSE_BASIS, DELETE), v.actions)
  }

  @Test
  fun priceOfAnotherAccountIsFlaggedBeforeWorkerRuns() {
    // Offline worker neběží a položka je pořád WAITING — obrazovka nesmí slibovat „odešle se sama".
    val v = view(price(ownerUid = "u1"), loggedIn = true, currentUid = "u2", online = false)
    assertEquals(UiText.Res(R.string.pending_price_status_requires_login), v.title)
    assertTrue(v.needsAttention)
    // Přihlášený jiný účet: „Přihlásit se" by nikam nevedlo, jen smazat.
    assertEquals(listOf(DELETE), v.actions)
  }

  @Test
  fun signedOutOwnerIsOfferedSignIn() {
    val v = view(price(PendingPriceState.FAILED, CODE_PRICE_REQUIRES_LOGIN, ownerUid = "u1"), loggedIn = false, currentUid = null)
    assertEquals(UiText.Res(R.string.pending_price_status_requires_login), v.title)
    assertEquals(listOf(LOG_IN, DELETE), v.actions)
  }

  @Test
  fun ownerBackAfterRejectionGetsRetry() {
    val v = view(price(PendingPriceState.FAILED, CODE_PRICE_REQUIRES_LOGIN, ownerUid = "u1"), currentUid = "u1")
    assertEquals(UiText.Res(R.string.pending_price_status_login_restored), v.title)
    assertEquals(listOf(RETRY, DELETE), v.actions)
  }

  @Test
  fun uidStillLoadingIsNotABlock() {
    // Hned po přihlášení se uid čte z Keystore — chvíli null. Nesmí probliknout „přihlas se".
    val v = view(price(ownerUid = "u1"), loggedIn = true, currentUid = null)
    assertFalse(v.needsAttention)
  }

  @Test
  fun anonymousPriceGoesUnderAnyAccount() {
    val v = view(price(ownerUid = null), loggedIn = true, currentUid = "u2")
    assertFalse(v.needsAttention)
  }

  @Test
  fun outdatedAppHasOwnText() {
    val v = view(price(PendingPriceState.FAILED, CODE_CLIENT_UPDATE_REQUIRED, productId = "p1"))
    assertEquals(UiText.Res(R.string.pending_price_status_update_required), v.title)
    assertEquals(listOf(RETRY, DELETE), v.actions)
  }

  @Test
  fun serverRejectionShowsServerMessageAsIs() {
    val v = view(price(PendingPriceState.FAILED, "OBSERVATION_PRICE_OUT_OF_RANGE", "Cena je mimo obvyklý rozsah.", productId = "p1"))
    assertEquals(UiText.Res(R.string.pending_price_status_failed), v.title)
    assertEquals(UiText.Raw("Cena je mimo obvyklý rozsah."), v.detail)
    assertEquals(listOf(RETRY, DELETE), v.actions)
  }

  @Test
  fun serverRejectionWithoutMessageFallsBackToGenericText() {
    val v = view(price(PendingPriceState.FAILED, "SOMETHING", "  ", productId = "p1"))
    assertEquals(UiText.Res(R.string.pending_price_status_failed_text), v.detail)
  }

  @Test
  fun sendNowOnlyWithConnectionAndSomethingSendable() {
    val waiting = price(productId = "p1")
    val stuck = price(PendingPriceState.NEEDS_PRODUCT, REASON_PRODUCT_UNKNOWN)
    assertTrue(canSendNow(listOf(waiting, stuck), loggedIn = true, currentUid = "u1", online = true, uploading = false))
    assertFalse(canSendNow(listOf(waiting), loggedIn = true, currentUid = "u1", online = false, uploading = false))
    assertFalse(canSendNow(listOf(waiting), loggedIn = true, currentUid = "u1", online = true, uploading = true))
    assertFalse(canSendNow(listOf(stuck), loggedIn = true, currentUid = "u1", online = true, uploading = false))
    // Cena jiného účtu odeslat nejde, i když je WAITING.
    assertFalse(
      canSendNow(listOf(price(ownerUid = "u1")), loggedIn = true, currentUid = "u2", online = true, uploading = false),
    )
  }

  @Test
  fun emptyQueueHasNoBadge() {
    val summary = pendingPriceSummary(emptyList(), loggedIn = true, currentUid = "u1")
    assertTrue(summary.isEmpty)
    assertNull(pendingPriceBadgeText(summary))
  }

  @Test
  fun badgeCountsWaitingPrices() {
    val summary = pendingPriceSummary(listOf(price(), price(productId = "p1")), loggedIn = true, currentUid = "u1")
    assertEquals(PendingPriceSummary(waiting = 2, needsAttention = 0), summary)
    assertEquals(UiText.Plural(R.plurals.pending_prices_badge_waiting, 2, listOf(2)), pendingPriceBadgeText(summary))
  }

  @Test
  fun badgePrefersPricesThatNeedUser() {
    val prices = listOf(
      price(),
      price(PendingPriceState.NEEDS_PRODUCT, REASON_PRODUCT_UNKNOWN),
      price(PendingPriceState.FAILED, "X"),
      price(ownerUid = "u9"),
    )
    val summary = pendingPriceSummary(prices, loggedIn = true, currentUid = "u1")
    assertEquals(PendingPriceSummary(waiting = 1, needsAttention = 3), summary)
    assertEquals(UiText.Plural(R.plurals.pending_prices_badge_attention, 3, listOf(3)), pendingPriceBadgeText(summary))
  }

  @Test
  fun chosenVariableWeightProductStillAsksForBasis() {
    // Formulář mohl založit zboží na váhu — cena zapsaná naslepo „za balení" nesmí odejít.
    assertEquals(
      LookupDecision.NeedsProduct(REASON_VARIABLE_WEIGHT, "p1"),
      decisionAfterProductChosen(price(PendingPriceState.NEEDS_PRODUCT, REASON_PRODUCT_UNKNOWN), "p1", isVariableWeight = true),
    )
    assertEquals(
      LookupDecision.Submit("p1"),
      decisionAfterProductChosen(price(PendingPriceState.NEEDS_PRODUCT, REASON_PRODUCT_UNKNOWN), "p1", isVariableWeight = false),
    )
    assertEquals(
      LookupDecision.Submit("p1"),
      decisionAfterProductChosen(price(basisChosenByUser = true), "p1", isVariableWeight = true),
    )
  }
}
