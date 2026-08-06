package cz.kvalitacena.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class OtpRequestBody(val email: String)

@Serializable
data class OtpRequestResponse(val challengeUid: String, val expiresInSec: Long, val resendAfterSec: Long)

@Serializable
data class OtpVerifyBody(val challengeUid: String, val code: String, val email: String)

@Serializable
data class RefreshBody(val refreshToken: String? = null)

@Serializable
data class TokenResponse(val accessToken: String, val refreshToken: String? = null, val newUser: Boolean = false)

@Serializable
data class Brand(val id: String, val name: String, val slug: String)

@Serializable
data class Category(val id: String, val name: String, val slug: String, val path: String)

@Serializable
data class RetailChain(val id: String, val name: String, val chainType: String)

@Serializable
data class Store(
  val id: String,
  val chain: RetailChain? = null,
  val name: String,
  val street: String? = null,
  val city: String,
  val postalCode: String? = null,
  val country: String,
  val lat: Double,
  val lon: Double,
)

@Serializable
data class PriceCurrent(
  val store: Store,
  val priceKind: String,
  val unitPrice: Double? = null,
  val priceAmount: Double? = null,
  val nObs: Int,
  val nEff: Double,
  val lastObservedAt: String? = null,
  val confidence: String,
)

@Serializable
data class Product(
  val id: String,
  val name: String,
  val brand: Brand? = null,
  val category: Category,
  val unitBase: String,
  val netContentValue: Double? = null,
  val netContentBase: Double,
  val piecesInPack: Int? = null,
  val isVariableWeight: Boolean,
  val status: String,
  val prices: List<PriceCurrent> = emptyList(),
  // Jen v detailu (PRODUCT_DETAIL_FIELDS) — productByCode je nežádá, viz GraphQlClient.
  val stats: ProductStats? = null,
  val quality: ProductQuality? = null,
  val myQualityRating: Int? = null,
  val externalLinks: List<ExternalLink> = emptyList(),
)

/** Lehčí varianta Product pro řádek seznamu hledání — bez cen a bez agregátů (ty jsou na ProductSearchItem). */
@Serializable
data class ProductSummary(
  val id: String,
  val name: String,
  val brand: Brand? = null,
  val category: Category,
)

@Serializable
data class ProductStats(
  val observationCount: Int,
  val storeCount: Int,
  val lastObservedAt: String? = null,
  val bestPrice: Double? = null,
  val bestUnitPrice: Double? = null,
  val cheapestStore: Store? = null,
)

/** Průměrná známka 1,00–5,00 (1 nejlepší, jako ve škole). average je null, dokud nikdo nehodnotil. */
@Serializable
data class ProductQuality(
  val average: Double? = null,
  val count: Int = 0,
)

@Serializable
data class ExternalLink(
  val kind: String,
  val label: String,
  val url: String,
  val attribution: String,
)

/** Řádek seznamu hledání — agregáty v rozsahu zvoleného filtru (obchod/město), viz backend ProductSearchItem. */
@Serializable
data class ProductSearchItem(
  val product: ProductSummary,
  val observationCount: Int,
  val bestPrice: Double? = null,
  val bestUnitPrice: Double? = null,
  val cheapestStore: Store? = null,
  val bestPriceObservations: Int? = null,
  val lastObservedAt: String? = null,
  val qualityAverage: Double? = null,
  val qualityCount: Int = 0,
)

@Serializable
data class ProductSearchResult(
  val items: List<ProductSearchItem> = emptyList(),
  val totalCount: Int = 0,
  val hasMore: Boolean = false,
)

@Serializable
data class SearchFacets(
  val stores: List<Store> = emptyList(),
  val cities: List<String> = emptyList(),
)

@Serializable
data class PricePoint(
  val day: String,
  val priceAmount: Double? = null,
  val unitPrice: Double,
  val nObs: Int,
  val storeCount: Int,
)

@Serializable
data class PriceHistory(
  val priceKind: String,
  val store: Store? = null,
  val days: Int,
  val points: List<PricePoint> = emptyList(),
)

/** Veřejná identita přihlášeného uživatele — bez e-mailu a bez DB id (docs/soukromi.md). */
@Serializable
data class Viewer(
  val publicHandle: String,
  val displayName: String? = null,
  val createdAt: String,
)

@Serializable
data class SubmitObservationInput(
  val productId: String,
  val storeId: String,
  val priceAmount: Double,
  val priceKind: String = "REGULAR",
  val quantityBasis: String = "PACKAGE",
)

@Serializable
data class PriceObservation(
  val id: String,
  val priceAmount: Double,
  val unitPrice: Double? = null,
  val priceKind: String,
  val quantityBasis: String,
  val observedAt: String,
  val status: String,
)

// --- Obálky GraphQL odpovědí (per dotaz/mutaci) ---

@Serializable
data class ProductByCodeData(val productByCode: Product? = null)

@Serializable
data class ProductData(val product: Product? = null)

@Serializable
data class NearbyStoresData(val nearbyStores: List<Store> = emptyList())

@Serializable
data class SubmitObservationData(val submitObservation: PriceObservation)

@Serializable
data class SearchProductsData(val searchProducts: ProductSearchResult)

@Serializable
data class SearchFacetsData(val searchFacets: SearchFacets)

@Serializable
data class PriceHistoryData(val priceHistory: PriceHistory)

@Serializable
data class RateProductData(val rateProduct: ProductQuality)

@Serializable
data class MeData(val me: Viewer? = null)

@Serializable
data class GraphQlError(val message: String)

@Serializable
data class GraphQlRequest(val query: String, val variables: JsonObject)

@Serializable
data class GraphQlResponse<T>(val data: T? = null, val errors: List<GraphQlError>? = null)
