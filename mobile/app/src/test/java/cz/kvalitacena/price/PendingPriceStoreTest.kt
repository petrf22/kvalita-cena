package cz.kvalitacena.price

import cz.kvalitacena.network.ObservationPriceInput
import cz.kvalitacena.network.Store
import java.io.File
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingPriceStoreTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val start = OffsetDateTime.parse("2026-11-16T10:00:00+01:00")

  private fun draft(
    barcode: String = "8594001234585",
    minute: Long = 0,
    productId: String? = null,
    submissionId: String = "",
  ) = pendingPriceDraft(
    submissionId = submissionId, ownerUid = "u1", productId = productId, barcode = barcode, productName = null,
    storeId = "s1", storeLabel = "Albert, Vzorov", currency = "CZK", productAlias = null,
    quantityBasis = "PACKAGE", observedAt = null,
    prices = listOf(
      ObservationPriceInput(priceKind = "REGULAR", priceAmount = 29.9),
      ObservationPriceInput(priceKind = "MULTIBUY", multibuyQty = 3, multibuyTotal = 79.0),
    ),
    now = start.plusMinutes(minute),
  )

  @Test
  fun zarazenaCenaPrezijeNovouInstanciUloziste() {
    val saved = PendingPriceStore(tmp.root).enqueue(draft())
    assertTrue(saved.id.isNotBlank())

    val reloaded = PendingPriceStore(tmp.root).pending.value
    assertEquals(listOf(saved), reloaded)
    assertEquals(2, reloaded.single().prices.size)
    assertEquals(3, reloaded.single().prices[1].multibuyQty)
  }

  @Test
  fun idOdeslaniZFormulareSeStaneIdemPolozky() {
    // Přímé odeslání, které selhalo na síti, mohlo na serveru projít — fronta musí poslat
    // stejné id (clientSubmissionId), jinak by server cenu zapsal podruhé.
    val saved = PendingPriceStore(tmp.root).enqueue(draft(submissionId = "6f1c2b7e-3a4d-4e5f-9a0b-1c2d3e4f5a6b"))
    assertEquals("6f1c2b7e-3a4d-4e5f-9a0b-1c2d3e4f5a6b", saved.id)
  }

  @Test
  fun frontaDrziPoradiZapisuAKazdaPolozkaMaVlastniId() {
    val store = PendingPriceStore(tmp.root)
    val second = store.enqueue(draft("222", minute = 5))
    val first = store.enqueue(draft("111", minute = 1))
    assertNotEquals(first.id, second.id)
    assertEquals(listOf("111", "222"), store.pending.value.map { it.barcode })
  }

  @Test
  fun neznameZboziCekaNaUzivateleAPoDoplneniSeVraciDoFronty() {
    val store = PendingPriceStore(tmp.root)
    val id = store.enqueue(draft()).id

    store.markNeedsProduct(id, REASON_PRODUCT_UNKNOWN)
    assertEquals(PendingPriceState.NEEDS_PRODUCT, store.get(id)!!.state)
    assertEquals(REASON_PRODUCT_UNKNOWN, store.get(id)!!.errorCode)

    store.resolveProduct(id, "p9", "Máslo 250 g")
    val resolved = store.get(id)!!
    assertEquals(PendingPriceState.WAITING, resolved.state)
    assertEquals("p9", resolved.productId)
    assertEquals("Máslo 250 g", resolved.productName)
    assertNull(resolved.errorCode)
  }

  @Test
  fun vahoveZboziSeOdblokujeJenVolbouZakladu() {
    val store = PendingPriceStore(tmp.root)
    val id = store.enqueue(draft()).id
    store.markNeedsProduct(id, REASON_VARIABLE_WEIGHT, productId = "p1", productName = "Rajčata")

    // „Zkusit znovu" tu nepomůže — odešla by cena za balení u zboží prodávaného na váhu.
    store.retry(id)
    assertEquals(PendingPriceState.NEEDS_PRODUCT, store.get(id)!!.state)

    store.chooseBasis(id, "PER_KG")
    val chosen = store.get(id)!!
    assertEquals(PendingPriceState.WAITING, chosen.state)
    assertEquals("PER_KG", chosen.quantityBasis)
    assertTrue(chosen.basisChosenByUser)
    assertEquals("p1", chosen.productId)
  }

  @Test
  fun selhanouCenuJdeZkusitZnovuNeboSmazat() {
    val store = PendingPriceStore(tmp.root)
    val id = store.enqueue(draft(productId = "p1")).id
    store.markFailed(id, "STORE_NOT_FOUND", "Obchod neexistuje")
    assertEquals("Obchod neexistuje", store.get(id)!!.errorMessage)

    store.retry(id)
    assertEquals(PendingPriceState.WAITING, store.get(id)!!.state)
    assertNull(store.get(id)!!.errorMessage)

    store.delete(id)
    assertTrue(store.pending.value.isEmpty())
    assertTrue(PendingPriceStore(tmp.root).pending.value.isEmpty())
  }

  @Test
  fun zmenaZJineInstanceSeNeprepiseStarymStavem() {
    // Worker a obrazovka můžou držet každý svou instanci nad stejným adresářem.
    val screen = PendingPriceStore(tmp.root)
    val id = screen.enqueue(draft()).id
    val worker = PendingPriceStore(tmp.root)
    worker.resolveProduct(id, "p1", "Máslo")

    screen.markFailed(id, "X", null)
    assertEquals("p1", PendingPriceStore(tmp.root).get(id)!!.productId)
  }

  @Test
  fun poskozenySouborNeshodiZbytekFronty() {
    val store = PendingPriceStore(tmp.root)
    store.enqueue(draft())
    File(tmp.root, "prices/pending/rozbity.json").writeText("{ useknuto")
    assertEquals(1, PendingPriceStore(tmp.root).pending.value.size)
  }

  @Test
  fun operaceNadSmazanouPolozkouNicNeudela() {
    val store = PendingPriceStore(tmp.root)
    store.markFailed("neexistuje", "X", null)
    store.resolveProduct("neexistuje", "p1", null)
    assertTrue(store.pending.value.isEmpty())
  }
}

class StoreCacheTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private fun store(id: String) = Store(id = id, name = "Obchod $id", city = "Vzorov", country = "CZ")

  @Test
  fun naposledyVideneJsouPrvniABezDuplicit() {
    var clock = 1_000L
    val cache = StoreCache(tmp.root) { clock }
    cache.remember(listOf(store("a"), store("b")))
    clock = 2_000L
    cache.remember(listOf(store("a").copy(name = "Přejmenovaný")))

    val all = StoreCache(tmp.root) { clock }.all()
    assertEquals(listOf("a", "b"), all.map { it.id })
    assertEquals("Přejmenovaný", all.first().name)
  }

  @Test
  fun stareObchodyVypadnouAPocetMaStrop() {
    var clock = 0L
    val cache = StoreCache(tmp.root) { clock }
    cache.remember(listOf(store("stary")))
    clock = StoreCache.MAX_AGE_MS + 1
    cache.remember((1..StoreCache.MAX_STORES + 5).map { store("n$it") })

    val all = cache.all()
    assertEquals(StoreCache.MAX_STORES, all.size)
    assertTrue(all.none { it.id == "stary" })
  }

  @Test
  fun poskozenaCacheJePrazdnaNeChyba() {
    File(tmp.root, "stores-seen.json").writeText("nesmysl")
    val cache = StoreCache(tmp.root)
    assertTrue(cache.all().isEmpty())
    cache.remember(listOf(store("a")))
    assertEquals(1, cache.all().size)
  }
}
