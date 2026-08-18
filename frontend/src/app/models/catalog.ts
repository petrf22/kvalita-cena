/**
 * Typy odvozené z generovaného GraphQL kontraktu (`npm run codegen`, viz `../../../codegen.ts`
 * a CLAUDE.md) — jediný zdroj pravdy je `backend/src/main/resources/graphql/schema.graphqls`.
 * Tenhle soubor sám žádný typ nedefinuje, jen z generovaných typů/fragmentů skládá pohodlnější
 * jména pro zbytek appky, ať 18 souborů, co odtud importuje, nemusí sahat přímo do
 * `models/generated/`. Doménové komentáře (proč je `lat` nullable, co znamená `verified`
 * apod.) jsou teď přímo ve `schema.graphqls` jako docstringy a codegen je propsal do
 * generovaných typů.
 */
import type {
  CompanyByIcoQuery,
  CreateProductInput,
  CreateStoreInput,
  FlagProductMutation,
  FlaggedRecordsQuery,
  FxInfoQuery,
  GeocodeAddressQuery,
  ModerationObservationsQuery,
  MyEditsQuery,
  MyObservationsQuery,
  MyProductsQuery,
  MyStoresQuery,
  PhotoFieldsFragment,
  PriceCurrentFieldsFragment,
  PriceHistoryQuery,
  ProductDetailFieldsFragment,
  ProductSummaryFieldsFragment,
  PublicationStatusFieldsFragment,
  ReverseGeocodeQuery,
  SearchFacetsQuery,
  SearchItemFieldsFragment,
  SearchProductsQuery,
  SearchStoresQuery,
  StoreDetailFieldsFragment,
  StoreFieldsFragment,
  SubmitObservationsInput,
  SubmitObservationsMutation,
  UpdateProductInput,
  UpdateStoreInput,
} from './generated/graphql';

export {
  Confidence,
  ExternalLinkKind,
  GeoSource,
  NetContentUom,
  PriceKind,
  ProductSort,
  ProductStatus,
  PublicationState,
  QuantityBasis,
  RecordType,
  UnitBase,
} from './generated/enums';

export type {
  CreateProductInput,
  CreateStoreInput,
  SubmitObservationsInput,
  UpdateProductInput,
  UpdateStoreInput,
};

export type Brand = NonNullable<ProductSummaryFieldsFragment['brand']>;
export type Category = ProductSummaryFieldsFragment['category'];
export type RetailChain = NonNullable<StoreFieldsFragment['chain']>;

/** Fotka zboží nebo provozovny (core.media) — nahrává se přes REST, ne přes tuhle mutaci. */
export type Photo = PhotoFieldsFragment;

/**
 * Obchod bez fotek (StoreFields) — pro řádky cen, výsledky hledání apod. `photos` je tu jen
 * optional, ať se dá stejný typ použít i pro `StoreDetailFields` (detail obchodu, kde fotky
 * skutečně přijdou); kde je fragment lehčí, pole prostě chybí.
 */
export type Store = StoreFieldsFragment & Partial<Pick<StoreDetailFieldsFragment, 'photos'>>;

export type PriceCurrent = PriceCurrentFieldsFragment;

/** Plný detail produktu (karta produktu) — vždy z fragmentu ProductDetailFields. */
export type Product = ProductDetailFieldsFragment;

/** "Vaše cena" — poslední vlastní zápis přihlášeného uživatele, i dřív než ho zpracuje agregace. */
export type MyPrice = ProductDetailFieldsFragment['myPrices'][number];

/** Lehčí varianta Product pro řádek seznamu hledání — bez cen a agregátů (ty jsou na ProductSearchItem). */
export type ProductSummary = ProductSummaryFieldsFragment;

export type ProductStats = ProductDetailFieldsFragment['stats'];

/** Průměrná známka 1,00–5,00 (1 nejlepší, jako ve škole). average je null, dokud nikdo nehodnotil. */
export type ProductQuality = ProductDetailFieldsFragment['quality'];

export type ExternalLink = ProductDetailFieldsFragment['externalLinks'][number];

/** Řádek seznamu hledání — agregáty spočítané spolu s hledáním, respektují filtr obchod/město. */
export type ProductSearchItem = SearchItemFieldsFragment;

export type ProductSearchResult = SearchProductsQuery['searchProducts'];
export type SearchFacets = SearchFacetsQuery['searchFacets'];
export type PricePoint = PriceHistoryQuery['priceHistory']['points'][number];
export type PriceHistory = PriceHistoryQuery['priceHistory'];
export type PriceObservation = SubmitObservationsMutation['submitObservations'][number];
export type StoreSearchResult = SearchStoresQuery['searchStores'];

/** Kandidát ze serveru OpenStreetMap Nominatim — nic z tohohle se neukládá kromě lat/lon/osmRef zvoleného kandidáta. */
export type GeocodeCandidate = GeocodeAddressQuery['geocodeAddress']['candidates'][number];
export type GeocodeResult = GeocodeAddressQuery['geocodeAddress'];

/** Opačný směr — souřadnice na adresu, pro tlačítko "Použít mou polohu" při editaci obchodu. */
export type ReverseGeocodeResult = ReverseGeocodeQuery['reverseGeocode'];

/** Údaje o firmě z veřejného rejstříku ARES podle IČO — předvyplnění formuláře obchodu. */
export type CompanyInfo = NonNullable<CompanyByIcoQuery['companyByIco']>;

export type FlagResult = FlagProductMutation['flagRecord'];

/** Zobrazovací měny a stav kurzovního lístku ČNB — pro atribuci na kartě Zdroje dat (docs/lokalizace.md). */
export type FxInfo = FxInfoQuery['fxInfo'];

/**
 * "Moje příspěvky" (docs/datovy-model.md, "Uživatelská vrstva nad globálními daty") — kdy se
 * vlastní záznam propaguje globálně, jeden typ pro všechny čtyři sekce výpisu.
 */
export type PublicationStatus = PublicationStatusFieldsFragment;
export type MyProductItem = MyProductsQuery['myProducts']['items'][number];
export type MyStoreItem = MyStoresQuery['myStores']['items'][number];
export type MyObservationItem = MyObservationsQuery['myObservations']['items'][number];
export type MyEditItem = MyEditsQuery['myEdits']['items'][number];

/** Fronta k přezkumu (docs/reputace.md, "Moderace") — jen moderátor (T4). */
export type FlaggedRecordItem = FlaggedRecordsQuery['flaggedRecords']['items'][number];

/** Cena k moderátorskému přezkumu — nesouhlas s cenou nejde nahlásit komunitně, viz setObservationRejected. */
export type ModerationObservationItem =
  ModerationObservationsQuery['moderationObservations']['items'][number];
