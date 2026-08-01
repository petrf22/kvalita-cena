import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { GraphQlService } from './graphql-service';
import { Product, PriceObservation, SubmitObservationInput } from '../models/catalog';

const PRICE_CURRENT_FIELDS = `
  store { id name street city postalCode country lat lon chain { id name chainType } }
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
  prices { ${PRICE_CURRENT_FIELDS} }
`;

@Injectable({ providedIn: 'root' })
export class ProductService {
  private readonly graphQl = inject(GraphQlService);

  search(query: string, first = 20): Observable<Product[]> {
    const gql = `
      query SearchProducts($query: String!, $first: Int) {
        searchProducts(query: $query, first: $first) { ${PRODUCT_FIELDS} }
      }
    `;
    return this.graphQl
      .execute<{ searchProducts: Product[] }>(gql, { query, first })
      .pipe(map((data) => data.searchProducts));
  }

  getById(id: string): Observable<Product | null> {
    const gql = `
      query Product($id: ID!) {
        product(id: $id) { ${PRODUCT_FIELDS} }
      }
    `;
    return this.graphQl.execute<{ product: Product | null }>(gql, { id }).pipe(map((data) => data.product));
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
