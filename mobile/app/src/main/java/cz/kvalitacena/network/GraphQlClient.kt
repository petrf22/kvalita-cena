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
  id name street city postalCode country lat lon geoSource ico chain { id name chainType }
  verified editedByMe pendingConfirmation
"""

private val PHOTO_FIELDS = """
  id url thumbnailUrl width height caption mine hidden attribution
"""

/** Navíc oproti STORE_FIELDS — jen pro detail obchodu, ať se fotky netahají všude, kde se
 * Store objeví (řádky cen, výsledky hledání, ...), stejný vzor jako u produktu. */
private val STORE_DETAIL_FIELDS = """
  $STORE_FIELDS
  photos { $PHOTO_FIELDS }
"""

private val PRICE_CURRENT_FIELDS = """
  store { $STORE_FIELDS }
  priceKind unitPrice priceAmount nObs nEff lastObservedAt confidence currency
"""

private val PRODUCT_FIELDS = """
  id name
  brand { id name slug }
  category { id name slug path }
  unitBase netContentValue netContentBase piecesInPack isVariableWeight status isGeneric
  verified editedByMe
  prices { $PRICE_CURRENT_FIELDS }
"""

/** Navíc oproti PRODUCT_FIELDS — jen pro obrazovku detailu, aby seznam hledání netahal zbytečně moc. */
private val PRODUCT_DETAIL_FIELDS = """
  $PRODUCT_FIELDS
  stats {
    observationCount storeCount lastObservedAt bestPrice bestUnitPrice bestPriceCurrency
    cheapestStore { $STORE_FIELDS }
  }
  quality { average count }
  myQualityRating
  externalLinks { kind label url attribution }
  myPrices { store { $STORE_FIELDS } priceKind priceAmount unitPrice observedAt currency }
  photos { $PHOTO_FIELDS }
"""

private val PRODUCT_SUMMARY_FIELDS = """
  id name
  brand { id name slug }
  category { id name slug path }
  isGeneric
  verified editedByMe
"""

private val SEARCH_ITEM_FIELDS = """
  product { $PRODUCT_SUMMARY_FIELDS }
  observationCount bestPrice bestUnitPrice bestPriceObservations lastObservedAt
  qualityAverage qualityCount currency
  cheapestStore { $STORE_FIELDS }
"""

/**
 * Bez Apollo — appka je malá, jeden POST /graphql endpoint stačí (stejná konvence jako
 * frontend/src/app/services/graphql-service.ts).
 */
class GraphQlClient(private val authRepository: AuthRepository, private val client: OkHttpClient) {

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
          priceKind days currency
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
    val gql = "{ me { publicHandle displayName createdAt trusted } }"
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

  /**
   * Našeptávač obchodů podle názvu/města — doplněk k nearbyStores pro zápis ceny bez sdílení
   * polohy nebo zpětně z domova (docs/datovy-model.md, "Identita provozovny").
   */
  suspend fun searchStores(query: String? = null, city: String? = null, first: Int = 20): StoreSearchResult {
    val gql = """
      query(${'$'}query: String, ${'$'}city: String, ${'$'}first: Int) {
        searchStores(query: ${'$'}query, city: ${'$'}city, first: ${'$'}first) {
          totalCount hasMore
          items { $STORE_FIELDS }
        }
      }
    """
    val variables = buildJsonObject {
      put("query", query)
      put("city", city)
      put("first", first)
    }
    return execute(gql, variables, GraphQlResponse.serializer(SearchStoresData.serializer())).searchStores
  }

  /** Podobné zboží podle názvu — nabídne existující druhové položky před založením nového (docs/reputace.md). */
  suspend fun productSuggestions(name: String, first: Int = 10): List<ProductSummary> {
    val gql = """
      query(${'$'}name: String!, ${'$'}first: Int) {
        productSuggestions(name: ${'$'}name, first: ${'$'}first) { $PRODUCT_SUMMARY_FIELDS }
      }
    """
    val variables = buildJsonObject {
      put("name", name)
      put("first", first)
    }
    return execute(gql, variables, GraphQlResponse.serializer(ProductSuggestionsData.serializer())).productSuggestions
  }

  /** Plochý seznam kategorií pro formulář nového zboží. */
  suspend fun categories(): List<Category> {
    val gql = "{ categories { id name slug path } }"
    return execute(gql, buildJsonObject {}, GraphQlResponse.serializer(CategoriesData.serializer())).categories
  }

  /**
   * Geokódování adresy přes server (nikdy přímo z appky, viz docs/soukromi.md). Chyba/výpadek
   * na backendu se vždy projeví jako prázdný seznam kandidátů, ne jako výjimka.
   */
  suspend fun geocodeAddress(street: String?, city: String, postalCode: String?): GeocodeResult {
    val gql = """
      query(${'$'}street: String, ${'$'}city: String!, ${'$'}postalCode: String) {
        geocodeAddress(street: ${'$'}street, city: ${'$'}city, postalCode: ${'$'}postalCode) {
          attribution
          candidates { lat lon displayName osmRef }
        }
      }
    """
    val variables = buildJsonObject {
      put("street", street)
      put("city", city)
      put("postalCode", postalCode)
    }
    return execute(gql, variables, GraphQlResponse.serializer(GeocodeAddressData.serializer())).geocodeAddress
  }

  /** Předvyplnění formuláře obchodu z veřejného rejstříku ARES — null, když IČO neexistuje nebo je ARES nedostupný. */
  suspend fun companyByIco(ico: String): CompanyInfo? {
    val gql = """
      query(${'$'}ico: String!) {
        companyByIco(ico: ${'$'}ico) { ico name street city postalCode }
      }
    """
    val variables = buildJsonObject { put("ico", ico) }
    return execute(gql, variables, GraphQlResponse.serializer(CompanyByIcoData.serializer())).companyByIco
  }

  /** Detail provozovny (adresa, mapa, fotky) — pro obrazovku obchodu, viz productById na produktu. */
  suspend fun storeById(id: String): Store? {
    val query = "query(${'$'}id: ID!) { store(id: ${'$'}id) { $STORE_DETAIL_FIELDS } }"
    val variables = buildJsonObject { put("id", id) }
    return execute(query, variables, GraphQlResponse.serializer(StoreData.serializer())).store
  }

  /**
   * Opačný směr než geocodeAddress — souřadnice na adresu, pro tlačítko "Použít mou polohu"
   * při editaci obchodu. Výpadek na backendu se projeví jako prázdná pole, ne jako výjimka.
   */
  suspend fun reverseGeocode(lat: Double, lon: Double): ReverseGeocodeResult {
    val query = """
      query(${'$'}lat: Float!, ${'$'}lon: Float!) {
        reverseGeocode(lat: ${'$'}lat, lon: ${'$'}lon) {
          street city postalCode country osmRef attribution
        }
      }
    """
    val variables = buildJsonObject {
      put("lat", lat)
      put("lon", lon)
    }
    return execute(query, variables, GraphQlResponse.serializer(ReverseGeocodeData.serializer())).reverseGeocode
  }

  /** Popisek a pořadí (nejnižší sortOrder = hlavní fotka záznamu). Jen autor fotky. */
  suspend fun updatePhoto(id: String, caption: String?, sortOrder: Int?): Photo {
    val query = """
      mutation(${'$'}id: ID!, ${'$'}caption: String, ${'$'}sortOrder: Int) {
        updatePhoto(id: ${'$'}id, caption: ${'$'}caption, sortOrder: ${'$'}sortOrder) { $PHOTO_FIELDS }
      }
    """
    val variables = buildJsonObject {
      put("id", id)
      put("caption", caption)
      put("sortOrder", sortOrder)
    }
    return execute(query, variables, GraphQlResponse.serializer(UpdatePhotoData.serializer())).updatePhoto
  }

  /** Smazání vlastní fotky. Jen autor. */
  suspend fun deletePhoto(id: String): Boolean {
    val query = "mutation(${'$'}id: ID!) { deletePhoto(id: ${'$'}id) }"
    val variables = buildJsonObject { put("id", id) }
    return execute(query, variables, GraphQlResponse.serializer(DeletePhotoData.serializer())).deletePhoto
  }

  /** Založení provozovny — vyžaduje přihlášení (docs/reputace.md, T1). */
  suspend fun createStore(input: CreateStoreInput): Store {
    val gql = """
      mutation(${'$'}input: CreateStoreInput!) {
        createStore(input: ${'$'}input) { $STORE_FIELDS }
      }
    """
    val variables = buildJsonObject {
      put("input", json.encodeToJsonElement(CreateStoreInput.serializer(), input))
    }
    return execute(gql, variables, GraphQlResponse.serializer(CreateStoreData.serializer())).createStore
  }

  /** Založení zboží — s naskenovaným EANem i bez něj (bezkódová druhová položka). Vyžaduje přihlášení. */
  suspend fun createProduct(input: CreateProductInput): Product {
    val gql = """
      mutation(${'$'}input: CreateProductInput!) {
        createProduct(input: ${'$'}input) { $PRODUCT_FIELDS }
      }
    """
    val variables = buildJsonObject {
      put("input", json.encodeToJsonElement(CreateProductInput.serializer(), input))
    }
    return execute(gql, variables, GraphQlResponse.serializer(CreateProductData.serializer())).createProduct
  }

  /**
   * Úprava existujícího zboží jako patch nad core.product_user_edit — globální řádek se
   * nemění, úpravu vidí jen autor (docs/datovy-model.md, "Uživatelská vrstva nad globálními
   * daty"). Vyžaduje přihlášení.
   */
  suspend fun updateProduct(id: String, input: UpdateProductInput): Product {
    val gql = """
      mutation(${'$'}id: ID!, ${'$'}input: UpdateProductInput!) {
        updateProduct(id: ${'$'}id, input: ${'$'}input) { $PRODUCT_DETAIL_FIELDS }
      }
    """
    val variables = buildJsonObject {
      put("id", id)
      put("input", json.encodeToJsonElement(UpdateProductInput.serializer(), input))
    }
    return execute(gql, variables, GraphQlResponse.serializer(UpdateProductData.serializer())).updateProduct
  }

  /** Úprava existující provozovny jako patch nad core.store_user_edit — vyžaduje přihlášení. */
  suspend fun updateStore(id: String, input: UpdateStoreInput): Store {
    val gql = """
      mutation(${'$'}id: ID!, ${'$'}input: UpdateStoreInput!) {
        updateStore(id: ${'$'}id, input: ${'$'}input) { $STORE_FIELDS }
      }
    """
    val variables = buildJsonObject {
      put("id", id)
      put("input", json.encodeToJsonElement(UpdateStoreInput.serializer(), input))
    }
    return execute(gql, variables, GraphQlResponse.serializer(UpdateStoreData.serializer())).updateStore
  }

  /**
   * Nahlášení zboží/obchodu jako podezřelého nebo nesmyslného — hlasuje se o faktu, nikdy o
   * člověku (docs/reputace.md, "Nesouhlas se vyjadřuje k faktu, ne k člověku"). Vyžaduje
   * přihlášení; opakované nahlášení stejným člověkem nic nemění.
   */
  suspend fun flagRecord(recordType: String, recordId: String, reason: String? = null): FlagResult {
    val gql = """
      mutation(${'$'}recordType: RecordType!, ${'$'}recordId: ID!, ${'$'}reason: String) {
        flagRecord(recordType: ${'$'}recordType, recordId: ${'$'}recordId, reason: ${'$'}reason) { flagCount hidden }
      }
    """
    val variables = buildJsonObject {
      put("recordType", recordType)
      put("recordId", recordId)
      put("reason", reason)
    }
    return execute(gql, variables, GraphQlResponse.serializer(FlagRecordData.serializer())).flagRecord
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
      if (!response.isSuccessful) {
        throw TransportException("GraphQL požadavek selhal (${response.code})")
      }
      val parsed = json.decodeFromString(responseSerializer, response.body!!.string())
      if (!parsed.errors.isNullOrEmpty()) {
        val first = parsed.errors.first()
        throw GraphQlAppException(
          first.extensions?.code,
          first.extensions?.params ?: emptyList(),
          parsed.errors.joinToString("; ") { it.message },
        )
      }
      parsed.data ?: throw TransportException("Prázdná odpověď od serveru")
    }
  }
}
