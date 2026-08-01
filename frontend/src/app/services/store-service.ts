import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { GraphQlService } from './graphql-service';
import { Store } from '../models/catalog';

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
        nearbyStores(lat: $lat, lon: $lon, radiusKm: $radiusKm) {
          id name street city postalCode country lat lon chain { id name chainType }
        }
      }
    `;
    return this.graphQl
      .execute<{ nearbyStores: Store[] }>(gql, { lat, lon, radiusKm })
      .pipe(map((data) => data.nearbyStores));
  }
}
