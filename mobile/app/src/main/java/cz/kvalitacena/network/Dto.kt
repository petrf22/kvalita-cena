package cz.kvalitacena.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class OtpRequestBody(val email: String)

@Serializable
data class OtpRequestResponse(val challengeUid: String, val expiresInSec: Long, val resendAfterSec: Long)

/**
 * [termsAccepted] se vyžaduje jen při JIT registraci nového účtu (docs/soukromi.md, "GDPR") —
 * backend ho ignoruje, pokud e-mail už patří existujícímu účtu (přihlášení, ne registrace).
 */
@Serializable
data class OtpVerifyBody(val challengeUid: String, val code: String, val email: String, val termsAccepted: Boolean)

@Serializable
data class RefreshBody(val refreshToken: String? = null)

@Serializable
data class TokenResponse(val accessToken: String, val refreshToken: String? = null, val newUser: Boolean = false)

@Serializable
data class Brand(val id: String, val name: String, val slug: String)

/**
 * [sortOrder] je pořadí SOUROZENCŮ v jedné větvi stromu (backend `schema.graphqls`, `type
 * Category`), ne globální řazení — jen `CategoryTree` (ui/common/CategoryTree.kt) ho čte, proto
 * default 0 pro dotazy, které ho neselectují (Product.category fragment v [GraphQlClient]).
 */
@Serializable
data class Category(val id: String, val name: String, val slug: String, val path: String, val sortOrder: Int = 0)

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
  // Nullable — provozovna zadaná bez GPS (zápis doma) může souřadnice dosud nemít
  // (docs/datovy-model.md, "Identita provozovny").
  val lat: Double? = null,
  val lon: Double? = null,
  val geoSource: String = "COMMUNITY",
  val ico: String? = null,
  // Odkaz na stránku provozovny (u řetězce) — volitelný, jako street/city jde přes
  // core.store_user_edit (docs/datovy-model.md).
  val url: String? = null,
  // Uživatelská vrstva nad globálními daty (docs/datovy-model.md) — konsolidační job zatím
  // neběží, takže verified je v etapě 1 vždy false.
  val verified: Boolean = false,
  val editedByMe: Boolean = false,
  // Provozovna od nedůvěryhodného autora čeká na potvrzení dalších přispěvatelů — vidí ji
  // jen autor (docs/reputace.md).
  val pendingConfirmation: Boolean = false,
  // Jen v detailu (STORE_DETAIL_FIELDS) — hledání/výběr obchodu je nežádá.
  val photos: List<Photo> = emptyList(),
)

/**
 * Fotka zboží nebo provozovny (core.media). url/thumbnailUrl jsou cesty na backend REST
 * (MediaController), appka si je musí složit s [ApiConfig.BASE_URL] — viz [Photo.fullUrl]/
 * [Photo.thumbUrl].
 */
@Serializable
data class Photo(
  val id: String,
  val url: String,
  val thumbnailUrl: String,
  val width: Int,
  val height: Int,
  val caption: String? = null,
  // Nahrál ji přihlášený uživatel — smí ji smazat a upravit popisek.
  val mine: Boolean = false,
  // Skrytá po nahlášení (docs/reputace.md) — vidí ji dál už jen autor.
  val hidden: Boolean = false,
  val attribution: String = "",
  // ITEM/LABEL/OTHER (docs/datovy-model.md) — enumy se tu mapují ručně na String, appka
  // z GraphQL schématu typy negeneruje (kořenový CLAUDE.md).
  val kind: String = "OTHER",
) {
  fun fullUrl(): String = ApiConfig.BASE_URL + url
  fun thumbUrl(): String = ApiConfig.BASE_URL + thumbnailUrl
}

/**
 * Přepočet do zobrazovací měny kurzem ČNB platným k danému dni (docs/lokalizace.md, "Kurzovní
 * lístek a zobrazovací měna") — appka ho posílá jen tehdy, když si uživatel zvolil jinou měnu
 * než "měnu obchodu" (viz DisplayCurrencyInterceptor); jinak je pole vždy null a appka ukáže originál.
 */
@Serializable
data class ConvertedPrice(
  val amount: Double,
  val currency: String,
  val rateDate: String,
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
  // ISO-4217 kód (docs/lokalizace.md) — vždy vyplněné, appka ho nesmí nahradit natvrdo "Kč".
  val currency: String,
  val converted: ConvertedPrice? = null,
  // MAX(promoValidTo) napříč observacemi buňky (jen PROMO) — vypršelou akci server do
  // Product.prices vůbec nevrátí (docs/datovy-model.md), takže je-li vyplněné, akce ještě platí.
  val promoValidTo: String? = null,
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
  // Druhová položka bez čárového kódu — viz docs/reputace.md, "Zboží bez čárového kódu".
  val isGeneric: Boolean = false,
  val prices: List<PriceCurrent> = emptyList(),
  // Jen v detailu (PRODUCT_DETAIL_FIELDS) — productByCode je nežádá, viz GraphQlClient.
  val stats: ProductStats? = null,
  val quality: ProductQuality? = null,
  val myQualityRating: Int? = null,
  val externalLinks: List<ExternalLink> = emptyList(),
  // Uživatelská vrstva nad globálními daty (docs/datovy-model.md) — konsolidační job zatím
  // neběží, takže verified je v etapě 1 vždy false.
  val verified: Boolean = false,
  val editedByMe: Boolean = false,
  // Jen v detailu — vlastní poslední zápisy ("Vaše cena"), viz PRODUCT_DETAIL_FIELDS.
  val myPrices: List<MyPrice> = emptyList(),
  // Jen v detailu — fotky zboží (core.media), první (nejnižší sortOrder) je hlavní.
  val photos: List<Photo> = emptyList(),
  val catalogSource: String = "COMMUNITY",
  val catalogAttribution: String? = null,
  val externalImage: ExternalProductImage? = null,
)

@Serializable
data class ExternalProductImage(val url: String, val thumbnailUrl: String, val attribution: String)

@Serializable
data class ExternalProductCandidate(
  val code: String, val name: String? = null, val brandName: String? = null,
  val category: Category? = null, val unitBase: String? = null,
  val netContentValue: Double? = null, val netContentUom: String? = null,
  val image: ExternalProductImage? = null, val sourceUrl: String = "", val attribution: String = "",
)

@Serializable
data class ProductLookupResult(
  val status: String,
  val product: Product? = null,
  val candidate: ExternalProductCandidate? = null,
)

/** "Vaše cena" — poslední vlastní zápis přihlášeného uživatele, i dřív než ho zpracuje agregace. */
@Serializable
data class MyPrice(
  val store: Store,
  val priceKind: String,
  val priceAmount: Double,
  val unitPrice: Double? = null,
  val observedAt: String,
  val currency: String,
  val converted: ConvertedPrice? = null,
  val promoValidFrom: String? = null,
  val promoValidTo: String? = null,
)

/** Lehčí varianta Product pro řádek seznamu hledání — bez cen a bez agregátů (ty jsou na ProductSearchItem). */
@Serializable
data class ProductSummary(
  val id: String,
  val name: String,
  val brand: Brand? = null,
  val category: Category,
  val isGeneric: Boolean = false,
  val verified: Boolean = false,
  val editedByMe: Boolean = false,
)

@Serializable
data class ProductStats(
  val observationCount: Int,
  val storeCount: Int,
  val lastObservedAt: String? = null,
  val bestPrice: Double? = null,
  val bestUnitPrice: Double? = null,
  val cheapestStore: Store? = null,
  // Měna bestPrice/bestUnitPrice — null jen když je bestPrice null (docs/lokalizace.md).
  val bestPriceCurrency: String? = null,
  val bestPriceConverted: ConvertedPrice? = null,
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
  // Měna bestPrice/bestUnitPrice — null jen když je bestPrice null (docs/lokalizace.md).
  val currency: String? = null,
  val converted: ConvertedPrice? = null,
  val convertedUnit: ConvertedPrice? = null,
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
  // Kurz PLATNÝ K "day", NIKDY dnešní (docs/lokalizace.md) — jinak by graf v USD mísil pohyb
  // ceny s pohybem kurzu. Null, dokud appka řadu nepřepočítává (viz PriceHistory.displayCurrency).
  val convertedUnitPrice: Double? = null,
  val convertedPriceAmount: Double? = null,
)

@Serializable
data class PriceHistory(
  val priceKind: String,
  val store: Store? = null,
  val days: Int,
  // VŽDY vyplněná (docs/lokalizace.md) — bez storeId je to měna s nejvíc záznamy za dané období.
  val currency: String,
  // Vyplněná, jen když appka řadu skutečně přepočítala (X-Display-Currency).
  val displayCurrency: String? = null,
  val rateAttribution: String? = null,
  val points: List<PricePoint> = emptyList(),
)

/** Veřejná identita přihlášeného uživatele — bez e-mailu a bez DB id (docs/soukromi.md). */
@Serializable
data class Viewer(
  val publicHandle: String,
  val displayName: String? = null,
  val createdAt: String,
  // Práh důvěry (docs/reputace.md) — vysvětluje, proč nový obchod/zboží zatím vidí jen viewer sám.
  val trusted: Boolean = false,
  // Vyplněné jen v dotazech, které o něj request (viz GraphQlClient.me/updateProfile/deleteAvatar).
  val profile: Profile? = null,
)

/**
 * Jeden řádek matice viditelnosti — existence záznamu (field, audience) znamená, že dané pole
 * je pro dané publikum viditelné (docs/soukromi.md, "Profil uživatele a viditelnost"). `field`
 * je jedna z FIRST_NAME/LAST_NAME/DISPLAY_NAME/CONTACT_EMAIL/PHONE/AVATAR, `audience` PUBLIC
 * nebo FRIENDS — appka zatím typy ze schématu negeneruje (network/Dto.kt mapuje enumy ručně na
 * String, viz CLAUDE.md).
 */
@Serializable
data class ProfileFieldAudience(
  val field: String,
  val audience: String,
)

/**
 * Profil přihlášeného uživatele — jméno, příjmení, telefon, kontaktní e-mail (nepovinné,
 * šifrované na backendu), avatar, viditelnost (docs/soukromi.md, "Profil uživatele a
 * viditelnost"). Vždy plný pohled vlastníka, nikdy filtrovaný podle `visibility`.
 */
@Serializable
data class Profile(
  val firstName: String? = null,
  val lastName: String? = null,
  val phone: String? = null,
  val contactEmail: String? = null,
  // Přihlašovací e-mail; mění se VÝHRADNĚ přes /api/auth/email/change (OTP na novou adresu).
  val loginEmail: String,
  val visibility: String = "ANONYMOUS",
  val visibleFields: List<ProfileFieldAudience> = emptyList(),
  val avatar: Photo? = null,
)

/** Patch nad auth.user_profile (+ app_user.display_name) — null u pole znamená "nezměněno". */
@Serializable
data class UpdateProfileInput(
  val firstName: String? = null,
  val clearFirstName: Boolean = false,
  val lastName: String? = null,
  val clearLastName: Boolean = false,
  val displayName: String? = null,
  val clearDisplayName: Boolean = false,
  val phone: String? = null,
  val clearPhone: Boolean = false,
  val contactEmail: String? = null,
  val clearContactEmail: Boolean = false,
  val visibility: String? = null,
  // null nechá matici beze změny, JAKÝKOLI seznam (i prázdný) ji celou nahradí.
  val visibleFields: List<ProfileFieldAudience>? = null,
)

// --- REST DTO pro změnu přihlašovacího e-mailu (/api/auth/email/change/*, ne GraphQL) ---

@Serializable
data class EmailChangeRequestBody(val email: String)

@Serializable
data class EmailChangeConfirmBody(val challengeUid: String, val code: String, val email: String)

// --- REST DTO pro výmaz účtu (/api/me/delete/*, ne GraphQL) — docs/soukromi.md, "GDPR" ---

@Serializable
data class AccountDeleteConfirmBody(val challengeUid: String, val code: String)

/**
 * Jeden řádek formuláře: druh ceny + částka. `priceKind` schválně BEZ Kotlin defaultu —
 * `encodeDefaults = false` (kotlinx.serialization výchozí, `GraphQlClient.json` ho nemění) by
 * hodnotu rovnou defaultu do JSONu vůbec nezakódoval, a server na `priceKind` v každém řádku
 * spoléhá vždy, ne jen když je jiný než REGULAR.
 */
@Serializable
data class ObservationPriceInput(
  val priceKind: String,
  val priceAmount: Double? = null,
  val multibuyQty: Int? = null,
  val multibuyTotal: Double? = null,
  // Platnost akce (jen PROMO, docs/datovy-model.md) — obě nepovinné, promoValidFrom nesmí
  // být v budoucnu (server to validuje, appka jen zrcadlí).
  val promoValidFrom: String? = null,
  val promoValidTo: String? = null,
)

/**
 * Víc cen z jedné cenovky (běžná + klubová + …) jedním voláním — `docs/datovy-model.md`,
 * "core.price_observation je jádro aplikace". Kolize jediného druhu ceny shodí celou dávku.
 */
@Serializable
data class SubmitObservationsInput(
  val productId: String,
  val storeId: String,
  val quantityBasis: String = "PACKAGE",
  val prices: List<ObservationPriceInput>,
)

@Serializable
data class StoreSearchResult(
  val items: List<Store> = emptyList(),
  val totalCount: Int = 0,
  val hasMore: Boolean = false,
)

/** Kandidát ze serveru OpenStreetMap Nominatim — nic z tohohle se neukládá kromě lat/lon/osmRef zvoleného kandidáta. */
@Serializable
data class GeocodeCandidate(
  val lat: Double,
  val lon: Double,
  val displayName: String,
  val osmRef: String,
)

@Serializable
data class GeocodeResult(
  val candidates: List<GeocodeCandidate> = emptyList(),
  val attribution: String,
)

/** Opačný směr — souřadnice na adresu, pro tlačítko "Použít mou polohu" při editaci obchodu. */
@Serializable
data class ReverseGeocodeResult(
  val street: String? = null,
  val city: String? = null,
  val postalCode: String? = null,
  val country: String? = null,
  val osmRef: String? = null,
  val attribution: String = "",
)

/** Údaje o firmě z veřejného rejstříku ARES podle IČO — předvyplnění formuláře obchodu. */
@Serializable
data class CompanyInfo(
  val ico: String,
  val name: String,
  val street: String? = null,
  val city: String? = null,
  val postalCode: String? = null,
)

/**
 * Číselník zemí, které appka zná (app.i18n.country-currency/country-locale na backendu) —
 * mobilní protějšek webového CountryInfo (frontend/src/app/services/country-service.ts).
 */
@Serializable
data class CountryInfo(
  val code: String,
  val currency: String,
  val defaultLocale: String? = null,
)

/** Odpověď setLocale mutace — appka hodnoty jen zahodí, jde jí čistě o vedlejší efekt na serveru. */
@Serializable
data class SetLocaleResult(val locale: String, val country: String? = null)

@Serializable
data class CreateStoreInput(
  val name: String,
  val chainId: String? = null,
  val street: String? = null,
  val city: String,
  val postalCode: String? = null,
  val country: String = "CZ",
  val ico: String? = null,
  val lat: Double? = null,
  val lon: Double? = null,
  val geoSource: String? = null,
  val osmRef: String? = null,
  val url: String? = null,
)

@Serializable
data class CreateProductInput(
  val name: String,
  val brandName: String? = null,
  val categoryId: String,
  val unitBase: String,
  val netContentValue: Double? = null,
  val netContentUom: String? = null,
  val piecesInPack: Int? = null,
  val isVariableWeight: Boolean = false,
  val code: String? = null,
)

/**
 * Založení identity nad potvrzeným OFF kandidátem (`ProductLookupResult.status == OFF_CANDIDATE`)
 * — na rozdíl od CreateProductInput jsou name/categoryId/unitBase nepovinné (OFF je může dodat
 * sám); `null` znamená "dál dodává OFF", appka ho posílá jen pro pole, která uživatel skutečně
 * změnil (`ui/product/ProductFormViewModel.kt`, zrcadlo webu).
 */
@Serializable
data class CreateProductFromOffInput(
  val code: String,
  val name: String? = null,
  val brandName: String? = null,
  val categoryId: String? = null,
  val unitBase: String? = null,
  val netContentValue: Double? = null,
  val netContentUom: String? = null,
  val piecesInPack: Int? = null,
  val isVariableWeight: Boolean = false,
)

/** Patch nad core.product_user_edit — pole null = nezměněno (docs/datovy-model.md). */
@Serializable
data class UpdateProductInput(
  val name: String? = null,
  val brandName: String? = null,
  val clearBrand: Boolean = false,
  val categoryId: String? = null,
  val unitBase: String? = null,
  val netContentValue: Double? = null,
  val netContentUom: String? = null,
  val piecesInPack: Int? = null,
  val clearPiecesInPack: Boolean = false,
  val isVariableWeight: Boolean? = null,
)

/** Patch nad core.store_user_edit — pole null = nezměněno. */
@Serializable
data class UpdateStoreInput(
  val name: String? = null,
  val chainId: String? = null,
  val clearChain: Boolean = false,
  val street: String? = null,
  val clearStreet: Boolean = false,
  val city: String? = null,
  val postalCode: String? = null,
  val clearPostalCode: Boolean = false,
  val country: String? = null,
  val ico: String? = null,
  val clearIco: Boolean = false,
  val lat: Double? = null,
  val lon: Double? = null,
  val geoSource: String? = null,
  val osmRef: String? = null,
  val url: String? = null,
  val clearUrl: Boolean = false,
)

/** Zobrazovací měny a stav kurzovního lístku ČNB — pro atribuci v Nastavení (docs/lokalizace.md). */
@Serializable
data class FxInfo(
  val displayCurrencies: List<String> = emptyList(),
  val latestRateDate: String? = null,
  val attribution: String,
)

/** Výsledek flagRecord — kolik různých lidí záznam nahlásilo a jestli je teď skrytý. */
@Serializable
data class FlagResult(
  val flagCount: Int,
  val hidden: Boolean,
)

/**
 * Zpětná vazba od uživatele appky (core.feedback) — na rozdíl od flagRecord funguje i BEZ
 * přihlášení (docs/nasazeni.md, "Než pozvat první lidi"). appVersion se posílá jako doplňková
 * informace, appka navíc posílá i X-Client-Version u KAŽDÉHO requestu (ClientVersionInterceptor).
 */
@Serializable
data class FeedbackInput(
  val category: String,
  val message: String,
  val contactEmail: String? = null,
  val pageRef: String? = null,
  val appVersion: String? = null,
  val diagnostics: String? = null,
)

@Serializable
data class FeedbackResult(val id: String)

@Serializable
data class PriceObservation(
  val id: String,
  val priceAmount: Double,
  val unitPrice: Double? = null,
  val priceKind: String,
  val quantityBasis: String,
  val promoValidFrom: String? = null,
  val promoValidTo: String? = null,
  val observedAt: String,
  val status: String,
)

// --- "Moje příspěvky" (docs/datovy-model.md, "Uživatelská vrstva nad globálními daty") ---

/**
 * Kdy se vlastní záznam propaguje globálně — jeden typ pro všechny čtyři sekce výpisu
 * (docs/reputace.md, "Práh důvěry pro zveřejnění nového záznamu"). [state] je jedna z
 * PUBLIC/AWAITING_CONFIRMATIONS/HIDDEN_AFTER_FLAGS/PENDING_MERGE (appka zatím typy ze schématu
 * negeneruje, viz CLAUDE.md). [confirmationsReceived]/[confirmationsRequired] jsou null mimo
 * AWAITING_CONFIRMATIONS.
 */
@Serializable
data class PublicationStatus(
  val state: String,
  val confirmationsReceived: Int? = null,
  val confirmationsRequired: Int? = null,
  val verified: Boolean = false,
)

@Serializable
data class MyProductItem(
  val product: ProductSummary,
  val createdAt: String,
  val publication: PublicationStatus,
)

@Serializable
data class MyProductResult(
  val items: List<MyProductItem> = emptyList(),
  val totalCount: Int = 0,
  val hasMore: Boolean = false,
)

@Serializable
data class MyStoreItem(
  val store: Store,
  val createdAt: String,
  val publication: PublicationStatus,
)

@Serializable
data class MyStoreResult(
  val items: List<MyStoreItem> = emptyList(),
  val totalCount: Int = 0,
  val hasMore: Boolean = false,
)

/**
 * Vlastní zapsaná cena ve výpisu "Moje příspěvky" — na rozdíl od [MyPrice] (poslední zápis na
 * kombinaci produkt/obchod/druh ceny, pro kartu "Vaše cena" na detailu produktu) je tohle plný
 * seznam všech vlastních zápisů, se stavem zveřejnění zděděným od blokujícího katalogového
 * záznamu (zboží NEBO obchod, cokoli z obou brání zveřejnění).
 */
@Serializable
data class MyObservationItem(
  val product: ProductSummary,
  val store: Store,
  val priceKind: String,
  val priceAmount: Double,
  val unitPrice: Double? = null,
  val currency: String,
  val converted: ConvertedPrice? = null,
  val promoValidFrom: String? = null,
  val promoValidTo: String? = null,
  val observedAt: String,
  val createdAt: String,
  val publication: PublicationStatus,
)

@Serializable
data class MyObservationResult(
  val items: List<MyObservationItem> = emptyList(),
  val totalCount: Int = 0,
  val hasMore: Boolean = false,
)

/**
 * Vlastní úprava cizího záznamu (core.product_user_edit / core.store_user_edit) — přesně
 * jeden z [product]/[store] je vyplněný, podle [recordType] (PRODUCT/STORE). Stav zveřejnění
 * je vždy PENDING_MERGE (konsolidační job zatím neběží).
 */
@Serializable
data class MyEditItem(
  val recordType: String,
  val product: ProductSummary? = null,
  val store: Store? = null,
  val updatedAt: String,
  val changedFields: List<String> = emptyList(),
  val publication: PublicationStatus,
)

@Serializable
data class MyEditResult(
  val items: List<MyEditItem> = emptyList(),
  val totalCount: Int = 0,
  val hasMore: Boolean = false,
)

// --- Obálky GraphQL odpovědí (per dotaz/mutaci) ---

@Serializable
data class ProductByCodeData(val productByCode: Product? = null)

@Serializable
data class ProductLookupByCodeData(val productLookupByCode: ProductLookupResult)

@Serializable
data class ProductData(val product: Product? = null)

@Serializable
data class NearbyStoresData(val nearbyStores: List<Store> = emptyList())

@Serializable
data class SubmitObservationsData(val submitObservations: List<PriceObservation>)

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
data class UpdateProfileData(val updateProfile: Viewer)

@Serializable
data class DeleteAvatarData(val deleteAvatar: Viewer)

@Serializable
data class SearchStoresData(val searchStores: StoreSearchResult)

@Serializable
data class ProductSuggestionsData(val productSuggestions: List<ProductSummary> = emptyList())

@Serializable
data class CategoriesData(val categories: List<Category> = emptyList())

@Serializable
data class GeocodeAddressData(val geocodeAddress: GeocodeResult)

@Serializable
data class CompanyByIcoData(val companyByIco: CompanyInfo? = null)

@Serializable
data class CreateStoreData(val createStore: Store)

@Serializable
data class CreateProductData(val createProduct: Product)

@Serializable
data class CreateProductFromOffData(val createProductFromOff: Product)

@Serializable
data class UpdateProductData(val updateProduct: Product)

@Serializable
data class UpdateStoreData(val updateStore: Store)

@Serializable
data class StoreData(val store: Store? = null)

@Serializable
data class ReverseGeocodeData(val reverseGeocode: ReverseGeocodeResult)

@Serializable
data class UpdatePhotoData(val updatePhoto: Photo)

@Serializable
data class DeletePhotoData(val deletePhoto: Boolean)

@Serializable
data class FlagRecordData(val flagRecord: FlagResult)

@Serializable
data class SubmitFeedbackData(val submitFeedback: FeedbackResult)

@Serializable
data class FxInfoData(val fxInfo: FxInfo)

@Serializable
data class CountriesData(val countries: List<CountryInfo> = emptyList())

@Serializable
data class ChainsData(val chains: List<RetailChain> = emptyList())

@Serializable
data class SetLocaleData(val setLocale: SetLocaleResult)

@Serializable
data class MyProductsData(val myProducts: MyProductResult)

@Serializable
data class MyStoresData(val myStores: MyStoreResult)

@Serializable
data class MyObservationsData(val myObservations: MyObservationResult)

@Serializable
data class MyEditsData(val myEdits: MyEditResult)

/**
 * `extensions.code`/`params` je strojový kontrakt chyby (docs/lokalizace.md), stejný jako
 * frontend `GraphQlAppError` — `message` je jen lokalizovaný fallback, appka ho ukáže, dokud
 * kód sama nezná (viz `ui/common/ErrorMessages.kt`, `toUiText()`). `classification` je Spring
 * for GraphQL `ErrorType` (`GraphQlExceptionHandler.errorTypeFor`) — appka ho čte jen jako
 * obecný signál "tenhle požadavek vyžadoval přihlášení a nemám ho" (`GraphQlClient.execute`,
 * `AuthRepository.recoverFromUnauthorized`), ne přes konkrétní `code`, protože vypršelý token
 * server od "nikdy nepřihlášen" nerozezná a mohl by vrátit kterýkoli z několika *_REQUIRES_LOGIN
 * kódů.
 */
@Serializable
data class GraphQlErrorExtensions(
  val code: String? = null,
  val params: List<JsonElement> = emptyList(),
  val classification: String? = null,
)

@Serializable
data class GraphQlError(val message: String, val extensions: GraphQlErrorExtensions? = null)

@Serializable
data class GraphQlRequest(val query: String, val variables: JsonObject)

@Serializable
data class GraphQlResponse<T>(val data: T? = null, val errors: List<GraphQlError>? = null)
