export type UnitBase = 'MASS' | 'VOLUME' | 'COUNT';
export type ProductStatus = 'DRAFT' | 'ACTIVE' | 'MERGED' | 'REJECTED';
export type PriceKind = 'REGULAR' | 'PROMO' | 'CLUB_CARD' | 'CLEARANCE' | 'MULTIBUY';
export type QuantityBasis = 'PACKAGE' | 'PER_KG' | 'PER_L' | 'PER_PIECE';
export type Confidence = 'LOW' | 'MEDIUM' | 'HIGH';

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
