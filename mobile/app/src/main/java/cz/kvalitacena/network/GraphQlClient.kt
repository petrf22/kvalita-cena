package cz.kvalitacena.network

import cz.kvalitacena.auth.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val PRICE_CURRENT_FIELDS = """
  store { id name street city postalCode country lat lon chain { id name chainType } }
  priceKind unitPrice priceAmount nObs nEff lastObservedAt confidence
"""

private val PRODUCT_FIELDS = """
  id name
  brand { id name slug }
  category { id name slug path }
  unitBase netContentValue netContentBase piecesInPack isVariableWeight status
  prices { $PRICE_CURRENT_FIELDS }
"""

/**
 * Bez Apollo — appka je malá, jeden POST /graphql endpoint stačí (stejná konvence jako
 * frontend/src/app/services/graphql-service.ts).
 */
class GraphQlClient(private val authRepository: AuthRepository) {

  private val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  suspend fun productByCode(code: String): Product? {
    val query = "query(${'$'}code: String!) { productByCode(code: ${'$'}code) { $PRODUCT_FIELDS } }"
    val variables = buildJsonObject { put("code", code) }
    return execute(query, variables, GraphQlResponse.serializer(ProductByCodeData.serializer())).productByCode
  }

  suspend fun productById(id: String): Product? {
    val query = "query(${'$'}id: ID!) { product(id: ${'$'}id) { $PRODUCT_FIELDS } }"
    val variables = buildJsonObject { put("id", id) }
    return execute(query, variables, GraphQlResponse.serializer(ProductData.serializer())).product
  }

  suspend fun nearbyStores(lat: Double, lon: Double, radiusKm: Double = 5.0): List<Store> {
    val query = """
      query(${'$'}lat: Float!, ${'$'}lon: Float!, ${'$'}radiusKm: Float) {
        nearbyStores(lat: ${'$'}lat, lon: ${'$'}lon, radiusKm: ${'$'}radiusKm) {
          id name street city postalCode country lat lon chain { id name chainType }
        }
      }
    """
    val variables = buildJsonObject {
      put("lat", lat)
      put("lon", lon)
      put("radiusKm", radiusKm)
    }
    return execute(query, variables, GraphQlResponse.serializer(NearbyStoresData.serializer())).nearbyStores
  }

  suspend fun submitObservation(input: SubmitObservationInput): PriceObservation {
    val query = """
      mutation(${'$'}input: SubmitObservationInput!) {
        submitObservation(input: ${'$'}input) {
          id priceAmount unitPrice priceKind quantityBasis observedAt status
        }
      }
    """
    val variables = buildJsonObject {
      put("input", json.encodeToJsonElement(SubmitObservationInput.serializer(), input))
    }
    return execute(query, variables, GraphQlResponse.serializer(SubmitObservationData.serializer())).submitObservation
  }

  private suspend fun <T> execute(
    query: String,
    variables: JsonObject,
    responseSerializer: KSerializer<GraphQlResponse<T>>,
  ): T = withContext(Dispatchers.IO) {
    val requestBody = json.encodeToString(GraphQlRequest(query, variables)).toRequestBody(jsonMediaType)

    val builder = Request.Builder().url("${ApiConfig.BASE_URL}/graphql").post(requestBody)
    authRepository.accessToken.value?.let { builder.header("Authorization", "Bearer $it") }

    client.newCall(builder.build()).execute().use { response ->
      check(response.isSuccessful) { "GraphQL požadavek selhal (${response.code})" }
      val parsed = json.decodeFromString(responseSerializer, response.body!!.string())
      if (!parsed.errors.isNullOrEmpty()) {
        throw IllegalStateException(parsed.errors.joinToString("; ") { it.message })
      }
      parsed.data ?: throw IllegalStateException("Prázdná odpověď od serveru")
    }
  }
}
