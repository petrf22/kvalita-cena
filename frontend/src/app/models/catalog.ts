export type UnitBase = 'MASS' | 'VOLUME' | 'COUNT';
export type ProductStatus = 'DRAFT' | 'ACTIVE' | 'MERGED' | 'REJECTED';
export type PriceKind = 'REGULAR' | 'PROMO' | 'CLUB_CARD' | 'CLEARANCE' | 'MULTIBUY';
export type QuantityBasis = 'PACKAGE' | 'PER_KG' | 'PER_L' | 'PER_PIECE';
export type Confidence = 'LOW' | 'MEDIUM' | 'HIGH';
export type ProductSort = 'REPORT_COUNT' | 'PRICE_ASC' | 'QUALITY' | 'LAST_REPORTED' | 'NAME';
export type ExternalLinkKind = 'OPEN_FOOD_FACTS' | 'E_NUMBERS';

export interface Brand {
  id: string;
  name: string;
  slug: string;
}

export interface Category {
  id: string;
  name: string;
  slug: string;
  path: string;
}

export interface RetailChain {
  id: string;
  name: string;
  chainType: string;
}

export type GeoSource = 'COMMUNITY' | 'OSM';

export interface Store {
  id: string;
  chain: RetailChain | null;
  name: string;
  street: string | null;
  city: string;
  postalCode: string | null;
  country: string;
  // Nullable — provozovna zadaná bez GPS (zápis doma) může souřadnice dosud nemít
  // (docs/datovy-model.md, "Identita provozovny").
  lat: number | null;
  lon: number | null;
  geoSource: GeoSource;
  ico: string | null;
  // Uživatelská vrstva nad globálními daty (docs/datovy-model.md) — konsolidační job zatím
  // neběží, takže verified je v etapě 1 vždy false.
  verified: boolean;
  editedByMe: boolean;
  // Provozovna od nedůvěryhodného autora čeká na potvrzení dalších přispěvatelů — vidí ji jen
  // autor (docs/reputace.md).
  pendingConfirmation: boolean;
}

export interface PriceCurrent {
  store: Store;
  priceKind: PriceKind;
  unitPrice: number | null;
  priceAmount: number | null;
  nObs: number;
  nEff: number;
  lastObservedAt: string | null;
  confidence: Confidence;
}

export interface Product {
  id: string;
  name: string;
  brand: Brand | null;
  category: Category;
  unitBase: UnitBase;
  netContentValue: number | null;
  netContentBase: number;
  piecesInPack: number | null;
  isVariableWeight: boolean;
  status: ProductStatus;
  // Druhová položka bez čárového kódu — viz docs/reputace.md, "Zboží bez čárového kódu".
  isGeneric: boolean;
  prices: PriceCurrent[];
  // Jen v detailu (fragment PRODUCT_DETAIL_FIELDS) — hledání je nežádá, viz product-service.
  gtin?: string | null;
  stats?: ProductStats;
  quality?: ProductQuality;
  myQualityRating?: number | null;
  externalLinks?: ExternalLink[];
  // Uživatelská vrstva nad globálními daty (docs/datovy-model.md) — konsolidační job zatím
  // neběží, takže verified je v etapě 1 vždy false.
  verified: boolean;
  editedByMe: boolean;
  // Jen v detailu — vlastní poslední zápisy ("Vaše cena"), viz PRODUCT_DETAIL_FIELDS.
  myPrices?: MyPrice[];
}

/** "Vaše cena" — poslední vlastní zápis přihlášeného uživatele, i dřív než ho zpracuje agregace. */
export interface MyPrice {
  store: Store;
  priceKind: PriceKind;
  priceAmount: number;
  unitPrice: number | null;
  observedAt: string;
}

/** Lehčí varianta Product pro řádek seznamu hledání — bez cen a agregátů (ty jsou na ProductSearchItem). */
export interface ProductSummary {
  id: string;
  name: string;
  brand: Brand | null;
  category: Category;
  isGeneric: boolean;
  verified: boolean;
  editedByMe: boolean;
}

export interface ProductStats {
  observationCount: number;
  storeCount: number;
  lastObservedAt: string | null;
  bestPrice: number | null;
  bestUnitPrice: number | null;
  cheapestStore: Store | null;
}

/** Průměrná známka 1,00–5,00 (1 nejlepší, jako ve škole). average je null, dokud nikdo nehodnotil. */
export interface ProductQuality {
  average: number | null;
  count: number;
}

export interface ExternalLink {
  kind: ExternalLinkKind;
  label: string;
  url: string;
  attribution: string;
}

/** Řádek seznamu hledání — agregáty spočítané spolu s hledáním, respektují filtr obchod/město. */
export interface ProductSearchItem {
  product: ProductSummary;
  observationCount: number;
  bestPrice: number | null;
  bestUnitPrice: number | null;
  cheapestStore: Store | null;
  bestPriceObservations: number | null;
  lastObservedAt: string | null;
  qualityAverage: number | null;
  qualityCount: number;
}

export interface ProductSearchResult {
  items: ProductSearchItem[];
  totalCount: number;
  hasMore: boolean;
}

export interface SearchFacets {
  stores: Store[];
  cities: string[];
}

export interface PricePoint {
  day: string;
  priceAmount: number | null;
  unitPrice: number;
  nObs: number;
  storeCount: number;
}

export interface PriceHistory {
  priceKind: PriceKind;
  store: Store | null;
  days: number;
  points: PricePoint[];
}

export interface PriceObservation {
  id: string;
  priceAmount: number;
  unitPrice: number | null;
  priceKind: PriceKind;
  quantityBasis: QuantityBasis;
  observedAt: string;
  status: string;
}

export interface SubmitObservationInput {
  productId: string;
  storeId: string;
  priceAmount: number;
  priceKind?: PriceKind;
  quantityBasis?: QuantityBasis;
  multibuyQty?: number;
  multibuyTotal?: number;
  observedAt?: string;
}

export interface StoreSearchResult {
  items: Store[];
  totalCount: number;
  hasMore: boolean;
}

/** Kandidát ze serveru OpenStreetMap Nominatim — nic z tohohle se neukládá kromě lat/lon/osmRef zvoleného kandidáta. */
export interface GeocodeCandidate {
  lat: number;
  lon: number;
  displayName: string;
  osmRef: string;
}

export interface GeocodeResult {
  candidates: GeocodeCandidate[];
  attribution: string;
}

/** Údaje o firmě z veřejného rejstříku ARES podle IČO — předvyplnění formuláře obchodu. */
export interface CompanyInfo {
  ico: string;
  name: string;
  street: string | null;
  city: string | null;
  postalCode: string | null;
}

export interface CreateStoreInput {
  name: string;
  chainId?: string | null;
  street?: string | null;
  city: string;
  postalCode?: string | null;
  country?: string;
  ico?: string | null;
  lat?: number | null;
  lon?: number | null;
  geoSource?: GeoSource | null;
  osmRef?: string | null;
}

export interface CreateProductInput {
  name: string;
  brandName?: string | null;
  categoryId: string;
  unitBase: UnitBase;
  netContentValue?: number | null;
  netContentUom?: string | null;
  piecesInPack?: number | null;
  isVariableWeight?: boolean;
  code?: string | null;
}

/** Patch nad core.product_user_edit — pole null = nezměněno (docs/datovy-model.md). */
export interface UpdateProductInput {
  name?: string | null;
  brandName?: string | null;
  clearBrand?: boolean;
  categoryId?: string | null;
  unitBase?: UnitBase | null;
  netContentValue?: number | null;
  netContentUom?: string | null;
  piecesInPack?: number | null;
  clearPiecesInPack?: boolean;
  isVariableWeight?: boolean | null;
}

/** Patch nad core.store_user_edit — pole null = nezměněno. */
export interface UpdateStoreInput {
  name?: string | null;
  chainId?: string | null;
  clearChain?: boolean;
  street?: string | null;
  clearStreet?: boolean;
  city?: string | null;
  postalCode?: string | null;
  clearPostalCode?: boolean;
  country?: string | null;
  ico?: string | null;
  clearIco?: boolean;
  lat?: number | null;
  lon?: number | null;
  geoSource?: GeoSource | null;
  osmRef?: string | null;
}

export type RecordType = 'PRODUCT' | 'STORE';

export interface FlagResult {
  flagCount: number;
  hidden: boolean;
}
