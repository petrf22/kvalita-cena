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
 * Globální zboží z účtenky (řádek s EAN) vezme kterýkoli obchod s cenou; lokální návrhy z něj
 * stejně odfiltruje [rematchCandidates].
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
  candidates.filter { it.id != source.id && scopeFits(source, it) && unitsFit(source, it) }

/** Co znamená jednotková cena zboží: za kg, za litr, za kus, nebo za balení neznámé velikosti. */
private enum class PriceUnit { MASS, VOLUME, COUNT, PACKAGE }

private fun priceUnit(unitBase: String?, netContentValue: Double?, variableWeight: Boolean): PriceUnit? = when {
  variableWeight -> if (unitBase == "VOLUME") PriceUnit.VOLUME else PriceUnit.MASS
  netContentValue == null -> PriceUnit.PACKAGE
  unitBase == "MASS" -> PriceUnit.MASS
  unitBase == "VOLUME" -> PriceUnit.VOLUME
  unitBase == "COUNT" -> PriceUnit.COUNT
  else -> null
}

/**
 * Ceny zboží z účtenky musí po dopárování znamenat totéž co ceny cíle (server `ReceiptItemUnits`,
 * `RECEIPT_ITEM_REMATCH_UNIT_MISMATCH`): stejný význam, nebo cena za balení neznámé velikosti na
 * zboží s pevnou gramáží (balení převezme gramáž cíle). Neznámou jednotku (starší server bez
 * `unitBase` v souhrnu) nechá rozhodnout server — tohle je jen předvýběr nabídky.
 */
internal fun unitsFit(source: Product, target: ProductSummary): Boolean {
  val from = priceUnit(source.unitBase, source.netContentValue, source.isVariableWeight) ?: return true
  val to = priceUnit(target.unitBase, target.netContentValue, target.isVariableWeight) ?: return true
  if (target.isVariableWeight && target.unitBase == null) return from != PriceUnit.PACKAGE
  return from == to || (from == PriceUnit.PACKAGE && !target.isVariableWeight && to != PriceUnit.PACKAGE)
}

private fun scopeFits(source: Product, target: ProductSummary): Boolean = when (target.catalogScope) {
  "CHAIN" -> source.catalogScope == "CHAIN" && target.scopeChain != null &&
    target.scopeChain.id == source.scopeChain?.id
  "STORE" -> source.catalogScope == "STORE" && target.scopeStore != null &&
    target.scopeStore.id == source.scopeStore?.id
  else -> true
}
