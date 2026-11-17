package cz.kvalitacena.price

import cz.kvalitacena.network.ClientUpdateRequiredException
import cz.kvalitacena.network.GraphQlAppException
import cz.kvalitacena.network.ObservationPriceInput
import cz.kvalitacena.network.TransportException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPriceLogicTest {

  private fun serverError(code: String) = GraphQlAppException(code, emptyList(), "zpráva serveru")

  @Test
  fun doFrontyJdeJenSelhaniSite() {
    assertTrue(shouldQueueAfterFailure(UnknownHostException("api")))
    assertTrue(shouldQueueAfterFailure(SocketTimeoutException()))
    assertTrue(shouldQueueAfterFailure(TransportException("GraphQL požadavek selhal (502)")))
    // Odpověď serveru s chybou je odpověď — uživatel ji musí vidět hned.
    assertFalse(shouldQueueAfterFailure(serverError("OBSERVATION_PRICE_INCOMPLETE")))
    assertFalse(shouldQueueAfterFailure(ClientUpdateRequiredException()))
    assertFalse(shouldQueueAfterFailure(IllegalStateException("chyba v appce")))
  }

  @Test
  fun zbozivKataloguSeOdesle() {
    assertEquals(
      LookupDecision.Submit("p1"),
      lookupDecision("EXISTING", "p1", isVariableWeight = false, basisChosenByUser = false),
    )
  }

  @Test
  fun vahoveZboziZapsaneNaslepoCekaNaVolbuZakladu() {
    assertEquals(
      LookupDecision.NeedsProduct(REASON_VARIABLE_WEIGHT, "p1"),
      lookupDecision("EXISTING", "p1", isVariableWeight = true, basisChosenByUser = false),
    )
    // Jakmile uživatel základ vybral, cena odejde.
    assertEquals(
      LookupDecision.Submit("p1"),
      lookupDecision("EXISTING", "p1", isVariableWeight = true, basisChosenByUser = true),
    )
  }

  @Test
  fun zboziZnameJenOpenFoodFactsSeNikdyNezakladaSamo() {
    assertEquals(
      LookupDecision.NeedsProduct(REASON_PRODUCT_UNKNOWN),
      lookupDecision("OFF_CANDIDATE", null, isVariableWeight = false, basisChosenByUser = false),
    )
    assertEquals(
      LookupDecision.NeedsProduct(REASON_PRODUCT_UNKNOWN),
      lookupDecision("NOT_FOUND", null, isVariableWeight = false, basisChosenByUser = false),
    )
    // EXISTING bez zboží (zamlčený cizí koncept) se chová jako neznámé.
    assertEquals(
      LookupDecision.NeedsProduct(REASON_PRODUCT_UNKNOWN),
      lookupDecision("EXISTING", null, isVariableWeight = false, basisChosenByUser = false),
    )
  }

  @Test
  fun nedostupneOpenFoodFactsNeniKonecnaOdpoved() {
    assertEquals(
      LookupDecision.Retry,
      lookupDecision("OFF_UNAVAILABLE", null, isVariableWeight = false, basisChosenByUser = false),
    )
  }

  private val regular = ObservationPriceInput(priceKind = "REGULAR", priceAmount = 29.9)
  private val clubCard = ObservationPriceInput(priceKind = "CLUB_CARD", priceAmount = 24.9)

  private fun kindCollision(kind: String) =
    GraphQlAppException(CODE_PRICE_KIND_ALREADY_SUBMITTED_TODAY, listOf(JsonPrimitive(kind)), "už zapsáno")

  @Test
  fun kolizeJednohoDruhuVyradiJenTenDruhZbytekOdejde() {
    // Server už má klubovou cenu a odmítl celou dávku — běžná cena se nesmí ztratit.
    assertEquals(listOf(regular), pricesAfterKindCollision(kindCollision("CLUB_CARD"), listOf(regular, clubCard)))
  }

  @Test
  fun kolizeJedinehoDruhuZnamenaVyrizenouPolozku() {
    assertEquals(emptyList<ObservationPriceInput>(), pricesAfterKindCollision(kindCollision("REGULAR"), listOf(regular)))
  }

  @Test
  fun kolizeKterouVyrazenimVyresitNejdeJdeDoBezneChyby() {
    // Druh, který dávka nemá — vyřazení by nic nezměnilo a worker by se zacyklil.
    assertNull(pricesAfterKindCollision(kindCollision("PROMO"), listOf(regular)))
    // Bez jména druhu (nebo úplně jiná chyba) není co vyřadit.
    assertNull(pricesAfterKindCollision(serverError(CODE_PRICE_KIND_ALREADY_SUBMITTED_TODAY), listOf(regular)))
    assertNull(pricesAfterKindCollision(serverError("STORE_NOT_FOUND"), listOf(regular)))
    assertNull(pricesAfterKindCollision(UnknownHostException("api"), listOf(regular)))
    assertEquals(SubmitFailureAction.FAILED, submitFailureAction(kindCollision("PROMO")))
  }

  @Test
  fun chybaServeruSeVeFronteNeopakujeDokola() {
    // Server ji vrací pořád stejně — automatické opakování by frontu zacyklilo, uživatel ji musí vidět.
    assertEquals(SubmitFailureAction.FAILED, submitFailureAction(serverError("OBSERVATION_ALREADY_SUBMITTED_TODAY")))
    assertEquals(SubmitFailureAction.FAILED, submitFailureAction(serverError("OBSERVATION_SUBMISSION_ID_CONFLICT")))
  }

  @Test
  fun chybaSiteSeOpakujeChybaServeruNe() {
    assertEquals(SubmitFailureAction.RETRY, submitFailureAction(UnknownHostException("api")))
    assertEquals(SubmitFailureAction.RETRY, submitFailureAction(TransportException("503")))
    assertEquals(SubmitFailureAction.FAILED, submitFailureAction(serverError("PRODUCT_NOT_AVAILABLE_AT_STORE")))
    assertEquals(SubmitFailureAction.FAILED, submitFailureAction(serverError("STORE_NOT_FOUND")))
    assertEquals(SubmitFailureAction.FAILED, submitFailureAction(ClientUpdateRequiredException()))
    assertEquals(SubmitFailureAction.FAILED, submitFailureAction(IllegalStateException()))
  }

  @Test
  fun cenaOdejdeJenPodUctemKteryJiZapsal() {
    assertEquals(OwnerCheck.OK, ownerCheck("u1", loggedIn = true, currentUid = "u1"))
    assertEquals(OwnerCheck.REQUIRES_LOGIN, ownerCheck("u1", loggedIn = true, currentUid = "u2"))
    assertEquals(OwnerCheck.REQUIRES_LOGIN, ownerCheck("u1", loggedIn = false, currentUid = null))
    // Přihlášený, ale uid se ještě neobnovilo — počkat, ne zahodit.
    assertEquals(OwnerCheck.WAIT, ownerCheck("u1", loggedIn = true, currentUid = null))
  }

  @Test
  fun anonymneZapsanaCenaOdejdeVzdy() {
    assertEquals(OwnerCheck.OK, ownerCheck(null, loggedIn = false, currentUid = null))
    assertEquals(OwnerCheck.OK, ownerCheck(null, loggedIn = true, currentUid = "u2"))
  }

  private val now = OffsetDateTime.parse("2026-11-16T10:15:30+01:00")
  private val prices = listOf(ObservationPriceInput(priceKind = "REGULAR", priceAmount = 29.9))

  @Test
  fun casPozorovaniJeVeFronteVzdyVyplneny() {
    // „Teď" z formuláře (null) nesmí ve frontě zůstat null — server by dosadil čas ODESLÁNÍ.
    assertEquals(now.toString(), observedAtForQueue(null, now))
    assertEquals("2026-11-10T12:00+01:00", observedAtForQueue("2026-11-10T12:00+01:00", now))
  }

  @Test
  fun zapisBezNactenehoZboziUkladaKodAZakladOdklada() {
    val draft = pendingPriceDraft(
      submissionId = "", ownerUid = "u1", productId = null, barcode = "8594001234585", productName = null,
      storeId = "s1", storeLabel = "Albert, Vzorov", currency = "CZK", productAlias = null,
      quantityBasis = "PER_KG", observedAt = null, prices = prices, now = now,
    )
    assertEquals("8594001234585", draft.barcode)
    assertEquals("PACKAGE", draft.quantityBasis)
    assertFalse(draft.basisChosenByUser)
    assertEquals(now.toString(), draft.observedAt)
    assertEquals(PendingPriceState.WAITING, draft.state)
  }

  @Test
  fun zapisSNactenymZbozimDrziZvolenyZaklad() {
    val draft = pendingPriceDraft(
      submissionId = "", ownerUid = null, productId = "p1", barcode = "8594001234585", productName = "Rajčata",
      storeId = "s1", storeLabel = "Albert, Vzorov", currency = "EUR", productAlias = "rajče",
      quantityBasis = "PER_KG", observedAt = null, prices = prices, now = now,
    )
    assertEquals("PER_KG", draft.quantityBasis)
    assertTrue(draft.basisChosenByUser)
    assertEquals("rajče", draft.productAlias)
    // Měna je jen pro zobrazení ve frontě — podle země obchodu, ne podle Nastavení.
    assertEquals("EUR", draft.currency)
  }

  @Test
  fun cenaOdlozenaKvuliPrihlaseniSeVraciJenSvemuVlastnikovi() {
    val waitingForLogin = PendingPrice(
      id = "x", ownerUid = "u1", productId = "p1", storeId = "s1", storeLabel = "x",
      observedAt = now.toString(), prices = prices, queuedAt = now.toString(),
      state = PendingPriceState.FAILED, errorCode = CODE_PRICE_REQUIRES_LOGIN,
    )
    assertTrue(revivesAfterLogin(waitingForLogin, loggedIn = true, currentUid = "u1"))
    assertFalse(revivesAfterLogin(waitingForLogin, loggedIn = true, currentUid = "u2"))
    assertFalse(revivesAfterLogin(waitingForLogin, loggedIn = false, currentUid = null))
    // Jiný důvod selhání přihlášení nespraví.
    assertFalse(revivesAfterLogin(waitingForLogin.copy(errorCode = "STORE_NOT_FOUND"), loggedIn = true, currentUid = "u1"))
    assertFalse(revivesAfterLogin(waitingForLogin.copy(state = PendingPriceState.WAITING), loggedIn = true, currentUid = "u1"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun cenaBezZboziIBezKoduSeNezaradi() {
    pendingPriceDraft(
      submissionId = "", ownerUid = null, productId = null, barcode = null, productName = null,
      storeId = "s1", storeLabel = "x", currency = null, productAlias = null,
      quantityBasis = "PACKAGE", observedAt = null, prices = prices, now = now,
    )
  }
}
