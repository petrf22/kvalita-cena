import { Injectable, inject } from '@angular/core';
import { map } from 'rxjs';
import { GraphQlService } from './graphql-service';
import { graphql } from '../models/generated';

@Injectable({ providedIn: 'root' })
export class ViewerService {
  private readonly graphQl = inject(GraphQlService);

  /** Veřejná identita přihlášeného uživatele — null pro anonyma. */
  me() {
    const document = graphql(`
      query Me {
        me {
          publicHandle
          displayName
          createdAt
          trusted
        }
      }
    `);
    return this.graphQl.execute(document).pipe(map((data) => data.me));
  }
}
