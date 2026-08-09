import { Injectable, inject } from '@angular/core';
import { map } from 'rxjs';
import { GraphQlService } from './graphql-service';
import { graphql } from '../models/generated';
import type { CreateStoreInput, UpdateStoreInput } from '../models/generated/graphql';

@Injectable({ providedIn: 'root' })
export class StoreService {
  private readonly graphQl = inject(GraphQlService);

  /**
   * Poloha se posílá jen jako parametr dotazu a nikam se v appce neukládá (docs/soukromi.md
   * v backendu) — zavolá se jednou při otevření formuláře a výsledek (seznam obchodů) se
   * uloží, souřadnice samotné appka dál nedrží.
   */
  nearby(lat: number, lon: number, radiusKm = 5) {
    const document = graphql(`
      query NearbyStores($lat: Float!, $lon: Float!, $radiusKm: Float) {
        nearbyStores(lat: $lat, lon: $lon, radiusKm: $radiusKm) {
          ...StoreFields
        }
      }
    `);
    return this.graphQl
      .execute(document, { lat, lon, radiusKm })
      .pipe(map((data) => data.nearbyStores));
  }

  /**
   * Našeptávač obchodů podle názvu/města — doplněk k nearby() pro zápis ceny bez sdílení
   * polohy nebo zpětně z domova (docs/datovy-model.md, "Identita provozovny").
   */
  search(query: string | null, city: string | null = null, first = 20) {
    const document = graphql(`
      query SearchStores($query: String, $city: String, $first: Int) {
        searchStores(query: $query, city: $city, first: $first) {
          totalCount
          hasMore
          items {
            ...StoreFields
          }
        }
      }
    `);
    return this.graphQl
      .execute(document, { query, city, first })
      .pipe(map((data) => data.searchStores));
  }

  /** Detail provozovny (adresa, mapa, fotky) — pro stránku obchodu, viz product(id) na produktu. */
  getById(id: string) {
    const document = graphql(`
      query Store($id: ID!) {
        store(id: $id) {
          ...StoreDetailFields
        }
      }
    `);
    return this.graphQl.execute(document, { id }).pipe(map((data) => data.store));
  }

  /** Založení provozovny — vyžaduje přihlášení (docs/reputace.md, T1). */
  create(input: CreateStoreInput) {
    const document = graphql(`
      mutation CreateStore($input: CreateStoreInput!) {
        createStore(input: $input) {
          ...StoreFields
        }
      }
    `);
    return this.graphQl.execute(document, { input }).pipe(map((data) => data.createStore));
  }

  /** Úprava existující provozovny jako patch nad core.store_user_edit — vyžaduje přihlášení. */
  update(id: string, input: UpdateStoreInput) {
    const document = graphql(`
      mutation UpdateStore($id: ID!, $input: UpdateStoreInput!) {
        updateStore(id: $id, input: $input) {
          ...StoreFields
        }
      }
    `);
    return this.graphQl.execute(document, { id, input }).pipe(map((data) => data.updateStore));
  }

  /** Nahlášení obchodu jako podezřelého/nesmyslného — hlasuje se o faktu, ne o člověku (docs/reputace.md). */
  flag(id: string, reason?: string) {
    const document = graphql(`
      mutation FlagStore($recordId: ID!, $reason: String) {
        flagRecord(recordType: STORE, recordId: $recordId, reason: $reason) {
          flagCount
          hidden
        }
      }
    `);
    return this.graphQl
      .execute(document, { recordId: id, reason: reason ?? null })
      .pipe(map((data) => data.flagRecord));
  }

  /**
   * Geokódování adresy přes server (nikdy přímo z prohlížeče, viz docs/soukromi.md — jinak
   * by šla na Nominatim přímo IP uživatele). Výpadek na backendu se projeví jako prázdný
   * seznam kandidátů, ne jako chyba.
   */
  geocode(street: string | null, city: string, postalCode: string | null) {
    const document = graphql(`
      query GeocodeAddress($street: String, $city: String!, $postalCode: String) {
        geocodeAddress(street: $street, city: $city, postalCode: $postalCode) {
          attribution
          candidates {
            lat
            lon
            displayName
            osmRef
          }
        }
      }
    `);
    return this.graphQl
      .execute(document, { street, city, postalCode })
      .pipe(map((data) => data.geocodeAddress));
  }

  /**
   * Opačný směr než geocode() — souřadnice na adresu, pro tlačítko "Použít mou polohu" při
   * editaci obchodu. Výpadek na backendu se projeví jako prázdná pole, ne jako chyba.
   */
  reverseGeocode(lat: number, lon: number) {
    const document = graphql(`
      query ReverseGeocode($lat: Float!, $lon: Float!) {
        reverseGeocode(lat: $lat, lon: $lon) {
          street
          city
          postalCode
          country
          osmRef
          attribution
        }
      }
    `);
    return this.graphQl.execute(document, { lat, lon }).pipe(map((data) => data.reverseGeocode));
  }

  /** Předvyplnění formuláře obchodu z veřejného rejstříku ARES — null, když IČO neexistuje nebo je ARES nedostupný. */
  companyByIco(ico: string) {
    const document = graphql(`
      query CompanyByIco($ico: String!) {
        companyByIco(ico: $ico) {
          ico
          name
          street
          city
          postalCode
        }
      }
    `);
    return this.graphQl.execute(document, { ico }).pipe(map((data) => data.companyByIco));
  }
}
