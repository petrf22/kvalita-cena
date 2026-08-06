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

export interface Store {
  id: string;
  chain: RetailChain | null;
  name: string;
  street: string | null;
  city: string;
  postalCode: string | null;
  country: string;
  lat: number;
  lon: number;
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
  prices: PriceCurrent[];
  // Jen v detailu (fragment PRODUCT_DETAIL_FIELDS) — hledání je nežádá, viz product-service.
  gtin?: string | null;
  stats?: ProductStats;
  quality?: ProductQuality;
  myQualityRating?: number | null;
  externalLinks?: ExternalLink[];
}

/** Lehčí varianta Product pro řádek seznamu hledání — bez cen a agregátů (ty jsou na ProductSearchItem). */
export interface ProductSummary {
  id: string;
  name: string;
  brand: Brand | null;
  category: Category;
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
}
