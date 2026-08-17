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

/** Profil uživatele (docs/soukromi.md, "Profil uživatele a viditelnost") — vždy plný pohled vlastníka. */
private val PROFILE_FIELDS = """
  firstName lastName phone contactEmail loginEmail visibility
  visibleFields { field audience }
  avatar { $PHOTO_FIELDS }
"""

/** Navíc oproti STORE_FIELDS — jen pro detail obchodu, ať se fotky netahají všude, kde se
 * Store objeví (řádky cen, výsledky hledání, ...), stejný vzor jako u produktu. */
private val STORE_DETAIL_FIELDS = """
  $STORE_FIELDS
  photos { $PHOTO_FIELDS }
"""

private val CONVERTED_PRICE_FIELDS = "amount currency rateDate"

private val PRICE_CURRENT_FIELDS = """
  store { $STORE_FIELDS }
  priceKind unitPrice priceAmount nObs nEff lastObservedAt confidence currency
  converted { $CONVERTED_PRICE_FIELDS }
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
    bestPriceConverted { $CONVERTED_PRICE_FIELDS }
    cheapestStore { $STORE_FIELDS }
  }
  quality { average count }
  myQualityRating
  externalLinks { kind label url attribution }
  myPrices {
    store { $STORE_FIELDS } priceKind priceAmount unitPrice observedAt currency
    converted { $CONVERTED_PRICE_FIELDS }
  }
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
  converted { $CONVERTED_PRICE_FIELDS }
  convertedUnit { $CONVERTED_PRICE_FIELDS }
  cheapestStore { $STORE_FIELDS }
"""

/**
 * Kdy se vlastní záznam propaguje globálně — jeden fragment pro všechny čtyři sekce "Moje
 * příspěvky" (docs/datovy-model.md, "Uživatelská vrstva nad globálními daty").
 */
private val PUBLICATION_STATUS_FIELDS = """
  state confirmationsReceived confirmationsRequired verified
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
   * Hledání s volitelným filtrem obchod/město/země a řazením — mobilní protějšek k
   * frontend product-service.ts. storeId/city/country null = appka nechá server dosadit
   * (viewerova země, jinak app.i18n.default-country) — country ale posíláme explicitně z
   * CountryStore (docs/lokalizace.md, "Country selector v UI"), appka je autoritativní.
   */
  suspend fun searchProducts(
    query: String,
    storeId: String? = null,
    city: String? = null,
    country: String? = null,
    sort: String = "REPORT_COUNT",
    first: Int = 20,
    offset: Int = 0,
  ): ProductSearchResult {
    val gql = """
      query(${'$'}query: String!, ${'$'}storeId: ID, ${'$'}city: String, ${'$'}country: String, ${'$'}sort: ProductSort, ${'$'}first: Int, ${'$'}offset: Int) {
        searchProducts(query: ${'$'}query, storeId: ${'$'}storeId, city: ${'$'}city, country: ${'$'}country, sort: ${'$'}sort, first: ${'$'}first, offset: ${'$'}offset) {
          totalCount hasMore
          items { $SEARCH_ITEM_FIELDS }
        }
      }
    """
    val variables = buildJsonObject {
      put("query", query)
      put("storeId", storeId)
      put("city", city)
      put("country", country)
      put("sort", sort)
      put("first", first)
      put("offset", offset)
    }
    return execute(gql, variables, GraphQlResponse.serializer(SearchProductsData.serializer())).searchProducts
  }

  /** Číselník obchodů/měst pro filtr hledání (jen ty, kde je skutečně nějaká cena). country viz searchProducts. */
  suspend fun searchFacets(country: String? = null): SearchFacets {
    val gql = """
      query(${'$'}country: String) {
        searchFacets(country: ${'$'}country) { cities stores { $STORE_FIELDS } }
      }
    """
    val variables = buildJsonObject { put("country", country) }
    return execute(gql, variables, GraphQlResponse.serializer(SearchFacetsData.serializer())).searchFacets
  }

  /** Číselník zemí, které appka zná (app.i18n.country-currency/country-locale) — mobilní protějšek country-service.ts. */
  suspend fun countries(): List<CountryInfo> {
    val gql = "{ countries { code currency defaultLocale } }"
    return execute(gql, buildJsonObject {}, GraphQlResponse.serializer(CountriesData.serializer())).countries
  }

  /**
   * Uloží preferovaný jazyk (a volitelně zemi) na server — VÝHRADNĚ pro asynchronní výstup
   * (OTP e-mail), appka z něj nikdy nerozhoduje o obsahu odpovědi (docs/lokalizace.md). Stejný
   * vzor jako web viewer-service.ts. Vyžaduje přihlášení.
   */
  suspend fun setLocale(locale: String, country: String? = null): SetLocaleResult {
    val gql = """
      mutation(${'$'}locale: String!, ${'$'}country: String) {
        setLocale(locale: ${'$'}locale, country: ${'$'}country) { locale country }
      }
    """
    val variables = buildJsonObject {
      put("locale", locale)
      put("country", country)
    }
    return execute(gql, variables, GraphQlResponse.serializer(SetLocaleData.serializer())).setLocale
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
          priceKind days currency displayCurrency rateAttribution
          store { $STORE_FIELDS }
          points { day priceAmount unitPrice nObs storeCount convertedUnitPrice convertedPriceAmount }
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
    val gql = "{ me { publicHandle displayName createdAt trusted profile { $PROFILE_FIELDS } } }"
    return execute(gql, buildJsonObject {}, GraphQlResponse.serializer(MeData.serializer())).me
  }

  /**
   * Jméno, příjmení, přezdívka, telefon, kontaktní e-mail, viditelnost (docs/soukromi.md,
   * "Profil uživatele a viditelnost"). Avatar se nahrává přes REST (MediaClient.uploadAvatar),
   * tahle mutace ho jen umí smazat (viz deleteAvatar). Vyžaduje přihlášení.
   */
  suspend fun updateProfile(input: UpdateProfileInput): Viewer {
    val gql = """
      mutation(${'$'}input: UpdateProfileInput!) {
        updateProfile(input: ${'$'}input) {
          publicHandle displayName
          profile { $PROFILE_FIELDS }
        }
      }
    """
    val variables = buildJsonObject {
      put("input", json.encodeToJsonElement(UpdateProfileInput.serializer(), input))
    }
    return execute(gql, variables, GraphQlResponse.serializer(UpdateProfileData.serializer())).updateProfile
  }

  /** Smazání avatara profilu. Vyžaduje přihlášení. */
  suspend fun deleteAvatar(): Viewer {
    val gql = """
      mutation {
        deleteAvatar {
          publicHandle displayName
          profile { $PROFILE_FIELDS }
        }
      }
    """
    return execute(gql, buildJsonObject {}, GraphQlResponse.serializer(DeleteAvatarData.serializer())).deleteAvatar
  }

  /** Zobrazovací měny a stav kurzovního lístku ČNB — pro atribuci v Nastavení (docs/lokalizace.md). */
  suspend fun fxInfo(): FxInfo {
    val gql = "{ fxInfo { displayCurrencies latestRateDate attribution } }"
    return execute(gql, buildJsonObject {}, GraphQlResponse.serializer(FxInfoData.serializer())).fxInfo
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

  /**
   * "Moje příspěvky" — vlastní založené zboží, nejnovější první, se stavem zveřejnění
   * (docs/datovy-model.md, "Uživatelská vrstva nad globálními daty"). Vyžaduje přihlášení.
   */
  suspend fun myProducts(first: Int = 20, offset: Int = 0): MyProductResult {
    val gql = """
      query(${'$'}first: Int, ${'$'}offset: Int) {
        myProducts(first: ${'$'}first, offset: ${'$'}offset) {
          totalCount hasMore
          items {
            createdAt
            publication { $PUBLICATION_STATUS_FIELDS }
            product { $PRODUCT_SUMMARY_FIELDS }
          }
        }
      }
    """
    val variables = buildJsonObject { put("first", first); put("offset", offset) }
    return execute(gql, variables, GraphQlResponse.serializer(MyProductsData.serializer())).myProducts
  }

  /** Vlastní založené provozovny, stejný princip jako [myProducts]. Vyžaduje přihlášení. */
  suspend fun myStores(first: Int = 20, offset: Int = 0): MyStoreResult {
    val gql = """
      query(${'$'}first: Int, ${'$'}offset: Int) {
        myStores(first: ${'$'}first, offset: ${'$'}offset) {
          totalCount hasMore
          items {
            createdAt
            publication { $PUBLICATION_STATUS_FIELDS }
            store { $STORE_FIELDS }
          }
        }
      }
    """
    val variables = buildJsonObject { put("first", first); put("offset", offset) }
    return execute(gql, variables, GraphQlResponse.serializer(MyStoresData.serializer())).myStores
  }

  /**
   * Vlastní zapsané ceny, nejnovější první — stav zveřejnění se dědí od blokujícího
   * katalogového záznamu (zboží/obchod), samotná cena žádný práh nemá. Vyžaduje přihlášení.
   */
  suspend fun myObservations(first: Int = 20, offset: Int = 0): MyObservationResult {
    val gql = """
      query(${'$'}first: Int, ${'$'}offset: Int) {
        myObservations(first: ${'$'}first, offset: ${'$'}offset) {
          totalCount hasMore
          items {
            priceKind priceAmount unitPrice currency observedAt createdAt
            converted { $CONVERTED_PRICE_FIELDS }
            publication { $PUBLICATION_STATUS_FIELDS }
            product { $PRODUCT_SUMMARY_FIELDS }
            store { $STORE_FIELDS }
          }
        }
      }
    """
    val variables = buildJsonObject { put("first", first); put("offset", offset) }
    return execute(gql, variables, GraphQlResponse.serializer(MyObservationsData.serializer())).myObservations
  }

  /** Vlastní úpravy CIZÍCH záznamů (core.product_user_edit/core.store_user_edit) — vždy PENDING_MERGE. */
  suspend fun myEdits(first: Int = 20, offset: Int = 0): MyEditResult {
    val gql = """
      query(${'$'}first: Int, ${'$'}offset: Int) {
        myEdits(first: ${'$'}first, offset: ${'$'}offset) {
          totalCount hasMore
          items {
            recordType updatedAt changedFields
            publication { $PUBLICATION_STATUS_FIELDS }
            product { $PRODUCT_SUMMARY_FIELDS }
            store { $STORE_FIELDS }
          }
        }
      }
    """
    val variables = buildJsonObject { put("first", first); put("offset", offset) }
    return execute(gql, variables, GraphQlResponse.serializer(MyEditsData.serializer())).myEdits
  }

  private suspend fun <T> execute(
    query: String,
    variables: JsonObject,
    responseSerializer: KSerializer<GraphQlResponse<T>>,
  ): T = withContext(Dispatchers.IO) {
    executeAttempt(query, variables, responseSerializer, allowRecovery = true)
  }

  /**
   * [allowRecovery] = false na opakovaném pokusu po [AuthRepository.recoverFromUnauthorized] —
   * jinak by nekonečně zkoušel refresh dokola, kdyby request selhával jako UNAUTHORIZED i
   * s čerstvým tokenem (např. účet mezitím zanikl).
   */
  private suspend fun <T> executeAttempt(
    query: String,
    variables: JsonObject,
    responseSerializer: KSerializer<GraphQlResponse<T>>,
    allowRecovery: Boolean,
  ): T {
    val hadToken = authRepository.accessToken.value != null
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
        // Vypršelý/neplatný access token vypadá pro server stejně jako "nikdy nepřihlášen"
        // (JwtAuthenticationFilter) — reagujeme proto na klasifikaci chyby, ne na konkrétní
        // *_REQUIRES_LOGIN kód, aby recovery fungovala pro libovolný chráněný dotaz.
        if (hadToken && allowRecovery && first.extensions?.classification == "UNAUTHORIZED"
          && authRepository.recoverFromUnauthorized()
        ) {
          return executeAttempt(query, variables, responseSerializer, allowRecovery = false)
        }
        throw GraphQlAppException(
          first.extensions?.code,
          first.extensions?.params ?: emptyList(),
          parsed.errors.joinToString("; ") { it.message },
        )
      }
      return parsed.data ?: throw TransportException("Prázdná odpověď od serveru")
    }
  }
}
