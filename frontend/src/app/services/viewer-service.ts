import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { GraphQlService } from './graphql-service';
import { Viewer } from '../models/auth';

@Injectable({ providedIn: 'root' })
export class ViewerService {
  private readonly graphQl = inject(GraphQlService);

  /** Veřejná identita přihlášeného uživatele — null pro anonyma. */
  me(): Observable<Viewer | null> {
    const gql = `{ me { publicHandle displayName createdAt trusted } }`;
    return this.graphQl.execute<{ me: Viewer | null }>(gql).pipe(map((data) => data.me));
  }
}
