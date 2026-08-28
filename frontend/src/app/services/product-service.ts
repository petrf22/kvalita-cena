import { Injectable, inject } from '@angular/core';
import { map } from 'rxjs';
import { GraphQlService } from './graphql-service';
import { graphql } from '../models/generated';
import type {
  CreateProductInput,
  PriceKind,
  ProductSort,
  SubmitObservationsInput,
  UpdateProductInput,
} from '../models/generated/graphql';

export interface SearchCriteria {
  query: string;
  storeId?: string | null;
  city?: string | null;
  /** VČETNĚ podstromu — vybraná "Mléčné výrobky" vrátí i Máslo a Sýry (schema.graphqls). */
  categoryId?: string | null;
  /** Nezadáno = server dosadí zemi přihlášeného uživatele, jinak app.i18n.default-country (docs/lokalizace.md). */
  country?: string | null;
  sort?: ProductSort;
  first?: number;
  offset?: number;
}

@Injectable({ providedIn: 'root' })
export class ProductService {
  private readonly graphQl = inject(GraphQlService);

  /** Hledání s volitelným filtrem obchod/město a řazením — viz zadání a schema.graphqls. */
  searchProducts(criteria: SearchCriteria) {
    const document = graphql(`
      query SearchProducts(
        $query: String!
        $storeId: ID
        $city: String
        $categoryId: ID
        $country: String
        $sort: ProductSort
        $first: Int
        $offset: Int
      ) {
        searchProducts(
          query: $query
          storeId: $storeId
          city: $city
          categoryId: $categoryId
          country: $country
          sort: $sort
          first: $first
          offset: $offset
        ) {
          totalCount
          hasMore
          items {
            ...SearchItemFields
          }
        }
      }
    `);
    return this.graphQl
      .execute(document, {
        query: criteria.query,
        storeId: criteria.storeId ?? null,
        city: criteria.city ?? null,
        categoryId: criteria.categoryId ?? null,
        country: criteria.country ?? null,
        sort: criteria.sort ?? 'REPORT_COUNT',
        first: criteria.first ?? 20,
        offset: criteria.offset ?? 0,
      })
      .pipe(map((data) => data.searchProducts));
  }

  /** Číselník obchodů/měst pro filtr hledání (jen ty, kde je skutečně nějaká cena). country viz searchProducts. */
  searchFacets(country?: string | null) {
    const document = graphql(`
      query SearchFacets($country: String) {
        searchFacets(country: $country) {
          cities
          stores {
            ...StoreFields
          }
        }
      }
    `);
    return this.graphQl
      .execute(document, { country: country ?? null })
      .pipe(map((data) => data.searchFacets));
  }

  /** Plný detail produktu (karta produktu) — na rozdíl od searchProducts tahá i stats/quality/externalLinks. */
  getById(id: string) {
    const document = graphql(`
      query Product($id: ID!) {
        product(id: $id) {
          ...ProductDetailFields
        }
      }
    `);
    return this.graphQl.execute(document, { id }).pipe(map((data) => data.product));
  }

  /** Dohledání podle naskenovaného/opsaného čárového kódu — backend normalizuje na GTIN-14. */
  getByCode(code: string) {
    const document = graphql(`
      query ProductByCode($code: String!) {
        productByCode(code: $code) {
          ...ProductDetailFields
        }
      }
    `);
    return this.graphQl.execute(document, { code }).pipe(map((data) => data.productByCode));
  }

  /** Lookup used by barcode entry: local product first, otherwise an editable OFF candidate. */
  lookupByCode(code: string) {
    const document = graphql(`
      query ProductLookupByCode($code: String!) {
        productLookupByCode(code: $code) {
          status
          product { ...ProductDetailFields }
          candidate {
            code name brandName category { id name slug path }
            unitBase netContentValue netContentUom
            image { url thumbnailUrl attribution }
            sourceUrl attribution
          }
        }
      }
    `);
    return this.graphQl.execute(document, { code }).pipe(map((data) => data.productLookupByCode));
  }

  /**
   * Podobné zboží podle názvu — nabídne existující druhové položky před založením nového
   * (docs/reputace.md, "Zboží bez čárového kódu") i jako "našli jsme podobné" krok obecně.
   */
  suggestions(name: string, first = 10) {
    const document = graphql(`
      query ProductSuggestions($name: String!, $first: Int) {
        productSuggestions(name: $name, first: $first) {
          ...ProductSummaryFields
        }
      }
    `);
    return this.graphQl
      .execute(document, { name, first })
      .pipe(map((data) => data.productSuggestions));
  }

  /**
   * Plochý seznam kategorií pro formulář nového zboží — `sortOrder` navíc oproti fragmentu
   * `ProductSummaryFields`, klient si z něj staví strom (`shared/category-tree.ts`).
   */
  categories() {
    const document = graphql(`
      query Categories {
        categories {
          id
          name
          slug
          path
          sortOrder
        }
      }
    `);
    return this.graphQl.execute(document).pipe(map((data) => data.categories));
  }

  /** Založení zboží — s naskenovaným EANem i bez něj (bezkódová druhová položka). Vyžaduje přihlášení. */
  createProduct(input: CreateProductInput) {
    const document = graphql(`
      mutation CreateProduct($input: CreateProductInput!) {
        createProduct(input: $input) {
          ...ProductDetailFields
        }
      }
    `);
    return this.graphQl.execute(document, { input }).pipe(map((data) => data.createProduct));
  }

  /**
   * Úprava existujícího zboží jako patch nad core.product_user_edit — globální řádek se
   * nemění, úpravu vidí jen autor (docs/datovy-model.md, "Uživatelská vrstva nad globálními
   * daty"). Vyžaduje přihlášení.
   */
  updateProduct(id: string, input: UpdateProductInput) {
    const document = graphql(`
      mutation UpdateProduct($id: ID!, $input: UpdateProductInput!) {
        updateProduct(id: $id, input: $input) {
          ...ProductDetailFields
        }
      }
    `);
    return this.graphQl.execute(document, { id, input }).pipe(map((data) => data.updateProduct));
  }

  /** Nahlášení zboží jako podezřelého/nesmyslného — hlasuje se o faktu, ne o člověku (docs/reputace.md). */
  flagProduct(id: string, reason?: string) {
    const document = graphql(`
      mutation FlagProduct($recordId: ID!, $reason: String) {
        flagRecord(recordType: PRODUCT, recordId: $recordId, reason: $reason) {
          flagCount
          hidden
        }
      }
    `);
    return this.graphQl
      .execute(document, { recordId: id, reason: reason ?? null })
      .pipe(map((data) => data.flagRecord));
  }

  /** Denní řada z agg.price_daily pro graf vývoje ceny — NIKDY ze syrových observací. */
  priceHistory(productId: string, priceKind: PriceKind = 'REGULAR', days = 90) {
    const document = graphql(`
      query PriceHistory($productId: ID!, $priceKind: PriceKind, $days: Int) {
        priceHistory(productId: $productId, priceKind: $priceKind, days: $days) {
          priceKind
          days
          currency
          displayCurrency
          rateAttribution
          store {
            ...StoreFields
          }
          points {
            day
            priceAmount
            unitPrice
            nObs
            storeCount
            convertedUnitPrice
            convertedPriceAmount
          }
        }
      }
    `);
    return this.graphQl
      .execute(document, { productId, priceKind, days })
      .pipe(map((data) => data.priceHistory));
  }

  /** Známka kvality 1–5 (1 nejlepší, jako ve škole) — vyžaduje přihlášení, jinak GraphQL UNAUTHORIZED. */
  rateProduct(productId: string, grade: number) {
    const document = graphql(`
      mutation RateProduct($productId: ID!, $grade: Int!) {
        rateProduct(productId: $productId, grade: $grade) {
          average
          count
        }
      }
    `);
    return this.graphQl
      .execute(document, { productId, grade })
      .pipe(map((data) => data.rateProduct));
  }

  /** Víc cen z jedné cenovky (běžná + klubová + …) jedním voláním — kolize jediného druhu shodí celou dávku. */
  submitObservations(input: SubmitObservationsInput) {
    const document = graphql(`
      mutation SubmitObservations($input: SubmitObservationsInput!) {
        submitObservations(input: $input) {
          id
          priceAmount
          currency
          unitPrice
          priceKind
          quantityBasis
          promoValidFrom
          promoValidTo
          observedAt
          status
        }
      }
    `);
    return this.graphQl.execute(document, { input }).pipe(map((data) => data.submitObservations));
  }
}
