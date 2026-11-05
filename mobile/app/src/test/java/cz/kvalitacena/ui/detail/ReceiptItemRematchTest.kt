package cz.kvalitacena.ui.detail

import cz.kvalitacena.network.Category
import cz.kvalitacena.network.MyPrice
import cz.kvalitacena.network.PriceCurrent
import cz.kvalitacena.network.Product
import cz.kvalitacena.network.ProductSummary
import cz.kvalitacena.network.RetailChain
import cz.kvalitacena.network.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptItemRematchTest {

  private val category = Category(id = "1", name = "Nezařazeno", slug = "nezarazeno", path = "nezarazeno")
  private val billa = RetailChain(id = "c1", name = "Billa", chainType = "CHAIN")
  private val lidl = RetailChain(id = "c2", name = "Lidl", chainType = "CHAIN")

  private fun store(id: String, chain: RetailChain?) =
    Store(id = id, chain = chain, name = "Obchod $id", city = "Brno", country = "CZ")

  private fun source(
    scope: String = "CHAIN",
    chain: RetailChain? = billa,
    scopeStore: Store? = null,
    prices: List<PriceCurrent> = emptyList(),
    myPrices: List<MyPrice> = emptyList(),
  ) = Product(
    id = "src",
    name = "Rohlík 43g",
    category = category,
    unitBase = "COUNT",
    netContentBase = 1.0,
    isVariableWeight = false,
    status = "DRAFT",
    isGeneric = true,
    catalogScope = scope,
    createdVia = "RECEIPT",
    scopeChain = chain,
    scopeStore = scopeStore,
    prices = prices,
    myPrices = myPrices,
  )

  private fun price(store: Store) = PriceCurrent(
    store = store, priceKind = "REGULAR", nObs = 1, nEff = 1.0, confidence = "LOW", currency = "CZK",
  )

  private fun myPrice(store: Store) = MyPrice(
    store = store, priceKind = "REGULAR", priceAmount = 3.9, observedAt = "2026-11-05T10:00:00Z", currency = "CZK",
  )

  private fun candidate(
    id: String,
    scope: String = "GLOBAL",
    chain: RetailChain? = null,
    scopeStore: Store? = null,
  ) = ProductSummary(
    id = id, name = "Rohlík", category = category, catalogScope = scope, scopeChain = chain, scopeStore = scopeStore,
  )

  @Test
  fun rozsahProvozovnyUrciObchodRovnou() {
    val shop = store("s9", null)
    assertEquals("s9", rematchStoreId(source(scope = "STORE", chain = null, scopeStore = shop)))
  }

  @Test
  fun uRetezceNejdrivVlastniCenaVObchoduTehozRetezce() {
    // Cizí cena v Lidlu se přeskočí, vlastní v Bille má přednost před cizí v Bille.
    val product = source(
      prices = listOf(price(store("s2", lidl)), price(store("s3", billa))),
      myPrices = listOf(myPrice(store("s1", billa))),
    )
    assertEquals("s1", rematchStoreId(product))
  }

  @Test
  fun bezCenyVRetezciObchodNeni() {
    assertNull(rematchStoreId(source(prices = listOf(price(store("s2", lidl))))))
  }

  @Test
  fun nabidkaVynechaZdrojACiziRozsah() {
    val candidates = listOf(
      candidate("src", scope = "CHAIN", chain = billa),
      candidate("ean"),
      candidate("legacy", scope = "LEGACY_GLOBAL"),
      candidate("billa", scope = "CHAIN", chain = billa),
      candidate("lidl", scope = "CHAIN", chain = lidl),
      candidate("store", scope = "STORE", scopeStore = store("s1", billa)),
    )
    assertEquals(listOf("ean", "legacy", "billa"), rematchCandidates(source(), candidates).map { it.id })
  }

  @Test
  fun zboziZUctenkyProvozovnyJdeJenNaStejnouProvozovnuNeboGlobalni() {
    val shop = store("s1", billa)
    val candidates = listOf(
      candidate("same", scope = "STORE", scopeStore = shop),
      candidate("other", scope = "STORE", scopeStore = store("s2", billa)),
      candidate("chain", scope = "CHAIN", chain = billa),
      candidate("ean"),
    )
    val product = source(scope = "STORE", chain = null, scopeStore = shop)
    assertEquals(listOf("same", "ean"), rematchCandidates(product, candidates).map { it.id })
  }

  @Test
  fun zboziZUctenkySEanJeGlobalniAJdeJenNaGlobalni() {
    val candidates = listOf(
      candidate("chain", scope = "CHAIN", chain = billa),
      candidate("store", scope = "STORE", scopeStore = store("s1", billa)),
      candidate("ean"),
    )
    val product = source(scope = "GLOBAL", chain = null)
    assertEquals(listOf("ean"), rematchCandidates(product, candidates).map { it.id })
  }
}
