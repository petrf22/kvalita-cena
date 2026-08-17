import { Injectable, inject } from '@angular/core';
import { map } from 'rxjs';
import { GraphQlService } from './graphql-service';
import { graphql } from '../models/generated';

/**
 * "Moje příspěvky" (docs/datovy-model.md, "Uživatelská vrstva nad globálními daty") — vlastní
 * založené zboží/obchody, vlastní zapsané ceny a vlastní úpravy cizích záznamů, každý se
 * stavem zveřejnění (`PublicationStatusFields`, graphql-fragments.ts). Všechny čtyři dotazy
 * vyžadují přihlášení (`error.contributions.requiresLogin`).
 */
@Injectable({ providedIn: 'root' })
export class MyContributionsService {
  private readonly graphQl = inject(GraphQlService);

  myProducts(first = 20, offset = 0) {
    const document = graphql(`
      query MyProducts($first: Int, $offset: Int) {
        myProducts(first: $first, offset: $offset) {
          totalCount
          hasMore
          items {
            createdAt
            publication {
              ...PublicationStatusFields
            }
            product {
              ...ProductSummaryFields
            }
          }
        }
      }
    `);
    return this.graphQl.execute(document, { first, offset }).pipe(map((data) => data.myProducts));
  }

  myStores(first = 20, offset = 0) {
    const document = graphql(`
      query MyStores($first: Int, $offset: Int) {
        myStores(first: $first, offset: $offset) {
          totalCount
          hasMore
          items {
            createdAt
            publication {
              ...PublicationStatusFields
            }
            store {
              ...StoreFields
            }
          }
        }
      }
    `);
    return this.graphQl.execute(document, { first, offset }).pipe(map((data) => data.myStores));
  }

  /** `converted` se doplní jen s aktivní `X-Display-Currency` hlavičkou (DisplayCurrencyInterceptor). */
  myObservations(first = 20, offset = 0) {
    const document = graphql(`
      query MyObservations($first: Int, $offset: Int) {
        myObservations(first: $first, offset: $offset) {
          totalCount
          hasMore
          items {
            priceKind
            priceAmount
            unitPrice
            currency
            observedAt
            createdAt
            converted {
              ...ConvertedPriceFields
            }
            publication {
              ...PublicationStatusFields
            }
            product {
              ...ProductSummaryFields
            }
            store {
              ...StoreFields
            }
          }
        }
      }
    `);
    return this.graphQl
      .execute(document, { first, offset })
      .pipe(map((data) => data.myObservations));
  }

  /** Vlastní úpravy CIZÍCH záznamů (core.product_user_edit/core.store_user_edit) — vždy PENDING_MERGE. */
  myEdits(first = 20, offset = 0) {
    const document = graphql(`
      query MyEdits($first: Int, $offset: Int) {
        myEdits(first: $first, offset: $offset) {
          totalCount
          hasMore
          items {
            recordType
            updatedAt
            changedFields
            publication {
              ...PublicationStatusFields
            }
            product {
              ...ProductSummaryFields
            }
            store {
              ...StoreFields
            }
          }
        }
      }
    `);
    return this.graphQl.execute(document, { first, offset }).pipe(map((data) => data.myEdits));
  }
}
