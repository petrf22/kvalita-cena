package cz.kvalitacena.ui.detail

import cz.kvalitacena.network.Product
import cz.kvalitacena.network.ProductSummary

// Čistá logika dopárování „zboží z účtenky" na skutečné zboží (`rematchReceiptItem`,
// docs/reputace.md, „Zboží z účtenky") — bez závislosti na Androidu, testovaná v
// ReceiptItemRematchTest.

/**
 * Obchod pro našeptávač (`productSuggestions(storeId)`): bez něj by nabídka ukázala jen globální
 * zboží s kódem, ne lokální bezkódové položky stejného řetězce/provozovny, na které zboží
 * z účtenky dopárovat jde. Rozsah provozovny ji rovnou určuje; u řetězce poslouží kterýkoli obchod
 * toho řetězce, kde zboží má cenu — nejdřív vlastní (víme jistě, kde ji uživatel viděl).
 */
internal fun rematchStoreId(product: Product): String? {
  product.scopeStore?.id?.let { return it }
  val chainId = product.scopeChain?.id
  val stores = product.myPrices.map { it.store } + product.prices.map { it.store }
  return stores.firstOrNull { chainId == null || it.chain?.id == chainId }?.id
}

/**
 * Návrhy, na které jde zboží z účtenky dopárovat: ne samo na sebe (našeptávač podle názvu ho
 * najde první) a jen zboží použitelné ve stejném rozsahu — stejné pravidlo jako
 * `ReceiptItemRematchService.scopeFits`, ať uživatel nevybere cíl, který server odmítne
 * (`RECEIPT_ITEM_REMATCH_INVALID`). Neznámý rozsah nechá rozhodnout server.
 */
internal fun rematchCandidates(source: Product, candidates: List<ProductSummary>): List<ProductSummary> =
  candidates.filter { it.id != source.id && scopeFits(source, it) }

private fun scopeFits(source: Product, target: ProductSummary): Boolean = when (target.catalogScope) {
  "CHAIN" -> source.catalogScope == "CHAIN" && target.scopeChain != null &&
    target.scopeChain.id == source.scopeChain?.id
  "STORE" -> source.catalogScope == "STORE" && target.scopeStore != null &&
    target.scopeStore.id == source.scopeStore?.id
  else -> true
}
