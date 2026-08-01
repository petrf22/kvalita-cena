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
data class GraphQlError(val message: String)

@Serializable
data class GraphQlRequest(val query: String, val variables: JsonObject)

@Serializable
data class GraphQlResponse<T>(val data: T? = null, val errors: List<GraphQlError>? = null)
