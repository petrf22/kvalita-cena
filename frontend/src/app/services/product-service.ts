import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { GraphQlService } from './graphql-service';
import {
  Category,
  CreateProductInput,
  FlagResult,
  Product,
  PriceHistory,
  PriceKind,
  PriceObservation,
  ProductQuality,
  ProductSearchResult,
  ProductSort,
  ProductSummary,
  SearchFacets,
  SubmitObservationInput,
  UpdateProductInput,
} from '../models/catalog';

const STORE_FIELDS = `
  id name street city postalCode country lat lon geoSource ico chain { id name chainType }
  verified editedByMe pendingConfirmation
`;

const PRICE_CURRENT_FIELDS = `
  store { ${STORE_FIELDS} }
  priceKind
  unitPrice
  priceAmount
  nObs
  nEff
  lastObservedAt
  confidence
`;

const PRODUCT_FIELDS = `
  id
  name
  brand { id name slug }
  category { id name slug path }
  unitBase
  netContentValue
  netContentBase
  piecesInPack
  isVariableWeight
  status
  isGeneric
  verified
  editedByMe
  prices { ${PRICE_CURRENT_FIELDS} }
`;

/** Navíc oproti PRODUCT_FIELDS — jen pro detail, aby hledání netahalo zbytečně moc. */
const PRODUCT_DETAIL_FIELDS = `
  ${PRODUCT_FIELDS}
  gtin
  stats { observationCount storeCount lastObservedAt bestPrice bestUnitPrice cheapestStore { ${STORE_FIELDS} } }
  quality { average count }
  myQualityRating
  externalLinks { kind label url attribution }
  myPrices { store { ${STORE_FIELDS} } priceKind priceAmount unitPrice observedAt }
`;

const PRODUCT_SUMMARY_FIELDS = `
  id
  name
  brand { id name slug }
  category { id name slug path }
  isGeneric
  verified
  editedByMe
`;

const SEARCH_ITEM_FIELDS = `
  product { ${PRODUCT_SUMMARY_FIELDS} }
  observationCount
  bestPrice
  bestUnitPrice
  bestPriceObservations
  lastObservedAt
  qualityAverage
  qualityCount
  cheapestStore { ${STORE_FIELDS} }
`;

export interface SearchCriteria {
  query: string;
  storeId?: string | null;
  city?: string | null;
  sort?: ProductSort;
  first?: number;
  offset?: number;
}

@Injectable({ providedIn: 'root' })
export class ProductService {
  private readonly graphQl = inject(GraphQlService);

  /** Hledání s volitelným filtrem obchod/město a řazením — viz zadání a schema.graphqls. */
  searchProducts(criteria: SearchCriteria): Observable<ProductSearchResult> {
    const gql = `
      query SearchProducts($query: String!, $storeId: ID, $city: String, $sort: ProductSort, $first: Int, $offset: Int) {
        searchProducts(query: $query, storeId: $storeId, city: $city, sort: $sort, first: $first, offset: $offset) {
          totalCount
          hasMore
          items { ${SEARCH_ITEM_FIELDS} }
        }
      }
    `;
    return this.graphQl
      .execute<{ searchProducts: ProductSearchResult }>(gql, {
        query: criteria.query,
        storeId: criteria.storeId ?? null,
        city: criteria.city ?? null,
        sort: criteria.sort ?? 'REPORT_COUNT',
        first: criteria.first ?? 20,
        offset: criteria.offset ?? 0,
      })
      .pipe(map((data) => data.searchProducts));
  }

  /** Číselník obchodů/měst pro filtr hledání (jen ty, kde je skutečně nějaká cena). */
  searchFacets(): Observable<SearchFacets> {
    const gql = `{ searchFacets { cities stores { ${STORE_FIELDS} } } }`;
    return this.graphQl.execute<{ searchFacets: SearchFacets }>(gql).pipe(map((data) => data.searchFacets));
  }

  /** Plný detail produktu (karta produktu) — na rozdíl od searchProducts tahá i stats/quality/externalLinks. */
  getById(id: string): Observable<Product | null> {
    const gql = `
      query Product($id: ID!) {
        product(id: $id) { ${PRODUCT_DETAIL_FIELDS} }
      }
    `;
    return this.graphQl.execute<{ product: Product | null }>(gql, { id }).pipe(map((data) => data.product));
  }

  /** Dohledání podle naskenovaného/opsaného čárového kódu — backend normalizuje na GTIN-14. */
  getByCode(code: string): Observable<Product | null> {
    const gql = `
      query ProductByCode($code: String!) {
        productByCode(code: $code) { ${PRODUCT_DETAIL_FIELDS} }
      }
    `;
    return this.graphQl
      .execute<{ productByCode: Product | null }>(gql, { code })
      .pipe(map((data) => data.productByCode));
  }

  /**
   * Podobné zboží podle názvu — nabídne existující druhové položky před založením nového
   * (docs/reputace.md, "Zboží bez čárového kódu") i jako "našli jsme podobné" krok obecně.
   */
  suggestions(name: string, first = 10): Observable<ProductSummary[]> {
    const gql = `
      query ProductSuggestions($name: String!, $first: Int) {
        productSuggestions(name: $name, first: $first) { ${PRODUCT_SUMMARY_FIELDS} }
      }
    `;
    return this.graphQl
      .execute<{ productSuggestions: ProductSummary[] }>(gql, { name, first })
      .pipe(map((data) => data.productSuggestions));
  }

  /** Plochý seznam kategorií pro formulář nového zboží. */
  categories(): Observable<Category[]> {
    const gql = `{ categories { id name slug path } }`;
    return this.graphQl.execute<{ categories: Category[] }>(gql).pipe(map((data) => data.categories));
  }

  /** Založení zboží — s naskenovaným EANem i bez něj (bezkódová druhová položka). Vyžaduje přihlášení. */
  createProduct(input: CreateProductInput): Observable<Product> {
    const gql = `
      mutation CreateProduct($input: CreateProductInput!) {
        createProduct(input: $input) { ${PRODUCT_FIELDS} }
      }
    `;
    return this.graphQl
      .execute<{ createProduct: Product }>(gql, { input })
      .pipe(map((data) => data.createProduct));
  }

  /**
   * Úprava existujícího zboží jako patch nad core.product_user_edit — globální řádek se
   * nemění, úpravu vidí jen autor (docs/datovy-model.md, "Uživatelská vrstva nad globálními
   * daty"). Vyžaduje přihlášení.
   */
  updateProduct(id: string, input: UpdateProductInput): Observable<Product> {
    const gql = `
      mutation UpdateProduct($id: ID!, $input: UpdateProductInput!) {
        updateProduct(id: $id, input: $input) { ${PRODUCT_DETAIL_FIELDS} }
      }
    `;
    return this.graphQl
      .execute<{ updateProduct: Product }>(gql, { id, input })
      .pipe(map((data) => data.updateProduct));
  }

  /** Nahlášení zboží jako podezřelého/nesmyslného — hlasuje se o faktu, ne o člověku (docs/reputace.md). */
  flagProduct(id: string, reason?: string): Observable<FlagResult> {
    const gql = `
      mutation FlagProduct($recordId: ID!, $reason: String) {
        flagRecord(recordType: PRODUCT, recordId: $recordId, reason: $reason) { flagCount hidden }
      }
    `;
    return this.graphQl
      .execute<{ flagRecord: FlagResult }>(gql, { recordId: id, reason: reason ?? null })
      .pipe(map((data) => data.flagRecord));
  }

  /** Denní řada z agg.price_daily pro graf vývoje ceny — NIKDY ze syrových observací. */
  priceHistory(productId: string, priceKind: PriceKind = 'REGULAR', days = 90): Observable<PriceHistory> {
    const gql = `
      query PriceHistory($productId: ID!, $priceKind: PriceKind, $days: Int) {
        priceHistory(productId: $productId, priceKind: $priceKind, days: $days) {
          priceKind
          days
          store { ${STORE_FIELDS} }
          points { day priceAmount unitPrice nObs storeCount }
        }
      }
    `;
    return this.graphQl
      .execute<{ priceHistory: PriceHistory }>(gql, { productId, priceKind, days })
      .pipe(map((data) => data.priceHistory));
  }

  /** Známka kvality 1–5 (1 nejlepší, jako ve škole) — vyžaduje přihlášení, jinak GraphQL UNAUTHORIZED. */
  rateProduct(productId: string, grade: number): Observable<ProductQuality> {
    const gql = `
      mutation RateProduct($productId: ID!, $grade: Int!) {
        rateProduct(productId: $productId, grade: $grade) { average count }
      }
    `;
    return this.graphQl
      .execute<{ rateProduct: ProductQuality }>(gql, { productId, grade })
      .pipe(map((data) => data.rateProduct));
  }

  submitObservation(input: SubmitObservationInput): Observable<PriceObservation> {
    const gql = `
      mutation SubmitObservation($input: SubmitObservationInput!) {
        submitObservation(input: $input) {
          id priceAmount unitPrice priceKind quantityBasis observedAt status
        }
      }
    `;
    return this.graphQl
      .execute<{ submitObservation: PriceObservation }>(gql, { input })
      .pipe(map((data) => data.submitObservation));
  }
}
