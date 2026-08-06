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

private val STORE_FIELDS = """
  id name street city postalCode country lat lon chain { id name chainType }
"""

private val PRICE_CURRENT_FIELDS = """
  store { $STORE_FIELDS }
  priceKind unitPrice priceAmount nObs nEff lastObservedAt confidence
"""

private val PRODUCT_FIELDS = """
  id name
  brand { id name slug }
  category { id name slug path }
  unitBase netContentValue netContentBase piecesInPack isVariableWeight status
  prices { $PRICE_CURRENT_FIELDS }
"""

/** Navíc oproti PRODUCT_FIELDS — jen pro obrazovku detailu, aby seznam hledání netahal zbytečně moc. */
private val PRODUCT_DETAIL_FIELDS = """
  $PRODUCT_FIELDS
  stats { observationCount storeCount lastObservedAt bestPrice bestUnitPrice cheapestStore { $STORE_FIELDS } }
  quality { average count }
  myQualityRating
  externalLinks { kind label url attribution }
"""

private val PRODUCT_SUMMARY_FIELDS = """
  id name
  brand { id name slug }
  category { id name slug path }
"""

private val SEARCH_ITEM_FIELDS = """
  product { $PRODUCT_SUMMARY_FIELDS }
  observationCount bestPrice bestUnitPrice bestPriceObservations lastObservedAt
  qualityAverage qualityCount
  cheapestStore { $STORE_FIELDS }
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

  /** Plný detail produktu (karta produktu) — na rozdíl od productByCode tahá i stats/quality/externalLinks. */
  suspend fun productById(id: String): Product? {
    val query = "query(${'$'}id: ID!) { product(id: ${'$'}id) { $PRODUCT_DETAIL_FIELDS } }"
    val variables = buildJsonObject { put("id", id) }
    return execute(query, variables, GraphQlResponse.serializer(ProductData.serializer())).product
  }

  suspend fun nearbyStores(lat: Double, lon: Double, radiusKm: Double = 5.0): List<Store> {
    val query = """
      query(${'$'}lat: Float!, ${'$'}lon: Float!, ${'$'}radiusKm: Float) {
        nearbyStores(lat: ${'$'}lat, lon: ${'$'}lon, radiusKm: ${'$'}radiusKm) { $STORE_FIELDS }
      }
    """
    val variables = buildJsonObject {
      put("lat", lat)
      put("lon", lon)
      put("radiusKm", radiusKm)
    }
    return execute(query, variables, GraphQlResponse.serializer(NearbyStoresData.serializer())).nearbyStores
  }

  /**
   * Hledání s volitelným filtrem obchod/město a řazením — mobilní protějšek k
   * frontend product-service.ts. storeId/city null = bez filtru.
   */
  suspend fun searchProducts(
    query: String,
    storeId: String? = null,
    city: String? = null,
    sort: String = "REPORT_COUNT",
    first: Int = 20,
    offset: Int = 0,
  ): ProductSearchResult {
    val gql = """
      query(${'$'}query: String!, ${'$'}storeId: ID, ${'$'}city: String, ${'$'}sort: ProductSort, ${'$'}first: Int, ${'$'}offset: Int) {
        searchProducts(query: ${'$'}query, storeId: ${'$'}storeId, city: ${'$'}city, sort: ${'$'}sort, first: ${'$'}first, offset: ${'$'}offset) {
          totalCount hasMore
          items { $SEARCH_ITEM_FIELDS }
        }
      }
    """
    val variables = buildJsonObject {
      put("query", query)
      put("storeId", storeId)
      put("city", city)
      put("sort", sort)
      put("first", first)
      put("offset", offset)
    }
    return execute(gql, variables, GraphQlResponse.serializer(SearchProductsData.serializer())).searchProducts
  }

  /** Číselník obchodů/měst pro filtr hledání (jen ty, kde je skutečně nějaká cena). */
  suspend fun searchFacets(): SearchFacets {
    val gql = "{ searchFacets { cities stores { $STORE_FIELDS } } }"
    return execute(gql, buildJsonObject {}, GraphQlResponse.serializer(SearchFacetsData.serializer())).searchFacets
  }

  /** Denní řada z agg.price_daily pro graf vývoje ceny — viz priceHistory v schema.graphqls. */
  suspend fun priceHistory(
    productId: String,
    priceKind: String = "REGULAR",
    storeId: String? = null,
    days: Int = 90,
  ): PriceHistory {
    val gql = """
      query(${'$'}productId: ID!, ${'$'}priceKind: PriceKind, ${'$'}storeId: ID, ${'$'}days: Int) {
        priceHistory(productId: ${'$'}productId, priceKind: ${'$'}priceKind, storeId: ${'$'}storeId, days: ${'$'}days) {
          priceKind days
          store { $STORE_FIELDS }
          points { day priceAmount unitPrice nObs storeCount }
        }
      }
    """
    val variables = buildJsonObject {
      put("productId", productId)
      put("priceKind", priceKind)
      put("storeId", storeId)
      put("days", days)
    }
    return execute(gql, variables, GraphQlResponse.serializer(PriceHistoryData.serializer())).priceHistory
  }

  /** Známka kvality 1–5 (1 nejlepší, jako ve škole) — vyžaduje přihlášení, jinak GraphQL UNAUTHORIZED. */
  suspend fun rateProduct(productId: String, grade: Int): ProductQuality {
    val gql = """
      mutation(${'$'}productId: ID!, ${'$'}grade: Int!) {
        rateProduct(productId: ${'$'}productId, grade: ${'$'}grade) { average count }
      }
    """
    val variables = buildJsonObject {
      put("productId", productId)
      put("grade", grade)
    }
    return execute(gql, variables, GraphQlResponse.serializer(RateProductData.serializer())).rateProduct
  }

  /** Veřejná identita přihlášeného uživatele — null pro anonyma. */
  suspend fun me(): Viewer? {
    val gql = "{ me { publicHandle displayName createdAt } }"
    return execute(gql, buildJsonObject {}, GraphQlResponse.serializer(MeData.serializer())).me
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
