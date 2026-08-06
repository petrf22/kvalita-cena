import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { GraphQlService } from './graphql-service';
import {
  CompanyInfo,
  CreateStoreInput,
  FlagResult,
  GeocodeResult,
  Store,
  StoreSearchResult,
  UpdateStoreInput,
} from '../models/catalog';

const STORE_FIELDS = `
  id name street city postalCode country lat lon geoSource ico chain { id name chainType }
  verified editedByMe pendingConfirmation
`;

@Injectable({ providedIn: 'root' })
export class StoreService {
  private readonly graphQl = inject(GraphQlService);

  /**
   * Poloha se posílá jen jako parametr dotazu a nikam se v appce neukládá (docs/soukromi.md
   * v backendu) — zavolá se jednou při otevření formuláře a výsledek (seznam obchodů) se
   * uloží, souřadnice samotné appka dál nedrží.
   */
  nearby(lat: number, lon: number, radiusKm = 5): Observable<Store[]> {
    const gql = `
      query NearbyStores($lat: Float!, $lon: Float!, $radiusKm: Float) {
        nearbyStores(lat: $lat, lon: $lon, radiusKm: $radiusKm) { ${STORE_FIELDS} }
      }
    `;
    return this.graphQl
      .execute<{ nearbyStores: Store[] }>(gql, { lat, lon, radiusKm })
      .pipe(map((data) => data.nearbyStores));
  }

  /**
   * Našeptávač obchodů podle názvu/města — doplněk k nearby() pro zápis ceny bez sdílení
   * polohy nebo zpětně z domova (docs/datovy-model.md, "Identita provozovny").
   */
  search(query: string | null, city: string | null = null, first = 20): Observable<StoreSearchResult> {
    const gql = `
      query SearchStores($query: String, $city: String, $first: Int) {
        searchStores(query: $query, city: $city, first: $first) {
          totalCount
          hasMore
          items { ${STORE_FIELDS} }
        }
      }
    `;
    return this.graphQl
      .execute<{ searchStores: StoreSearchResult }>(gql, { query, city, first })
      .pipe(map((data) => data.searchStores));
  }

  /** Založení provozovny — vyžaduje přihlášení (docs/reputace.md, T1). */
  create(input: CreateStoreInput): Observable<Store> {
    const gql = `
      mutation CreateStore($input: CreateStoreInput!) {
        createStore(input: $input) { ${STORE_FIELDS} }
      }
    `;
    return this.graphQl.execute<{ createStore: Store }>(gql, { input }).pipe(map((data) => data.createStore));
  }

  /** Úprava existující provozovny jako patch nad core.store_user_edit — vyžaduje přihlášení. */
  update(id: string, input: UpdateStoreInput): Observable<Store> {
    const gql = `
      mutation UpdateStore($id: ID!, $input: UpdateStoreInput!) {
        updateStore(id: $id, input: $input) { ${STORE_FIELDS} }
      }
    `;
    return this.graphQl.execute<{ updateStore: Store }>(gql, { id, input }).pipe(map((data) => data.updateStore));
  }

  /** Nahlášení obchodu jako podezřelého/nesmyslného — hlasuje se o faktu, ne o člověku (docs/reputace.md). */
  flag(id: string, reason?: string): Observable<FlagResult> {
    const gql = `
      mutation FlagStore($recordId: ID!, $reason: String) {
        flagRecord(recordType: STORE, recordId: $recordId, reason: $reason) { flagCount hidden }
      }
    `;
    return this.graphQl
      .execute<{ flagRecord: FlagResult }>(gql, { recordId: id, reason: reason ?? null })
      .pipe(map((data) => data.flagRecord));
  }

  /**
   * Geokódování adresy přes server (nikdy přímo z prohlížeče, viz docs/soukromi.md — jinak
   * by šla na Nominatim přímo IP uživatele). Výpadek na backendu se projeví jako prázdný
   * seznam kandidátů, ne jako chyba.
   */
  geocode(street: string | null, city: string, postalCode: string | null): Observable<GeocodeResult> {
    const gql = `
      query GeocodeAddress($street: String, $city: String!, $postalCode: String) {
        geocodeAddress(street: $street, city: $city, postalCode: $postalCode) {
          attribution
          candidates { lat lon displayName osmRef }
        }
      }
    `;
    return this.graphQl
      .execute<{ geocodeAddress: GeocodeResult }>(gql, { street, city, postalCode })
      .pipe(map((data) => data.geocodeAddress));
  }

  /** Předvyplnění formuláře obchodu z veřejného rejstříku ARES — null, když IČO neexistuje nebo je ARES nedostupný. */
  companyByIco(ico: string): Observable<CompanyInfo | null> {
    const gql = `
      query CompanyByIco($ico: String!) {
        companyByIco(ico: $ico) { ico name street city postalCode }
      }
    `;
    return this.graphQl
      .execute<{ companyByIco: CompanyInfo | null }>(gql, { ico })
      .pipe(map((data) => data.companyByIco));
  }
}
