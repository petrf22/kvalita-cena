import { Injectable, inject } from '@angular/core';
import { map } from 'rxjs';
import { GraphQlService } from './graphql-service';
import { graphql } from '../models/generated';

@Injectable({ providedIn: 'root' })
export class FxService {
  private readonly graphQl = inject(GraphQlService);

  /** Zobrazovací měny a stav kurzovního lístku ČNB — pro atribuci na kartě Zdroje dat (docs/lokalizace.md). */
  fxInfo() {
    const document = graphql(`
      query FxInfo {
        fxInfo {
          displayCurrencies
          latestRateDate
          attribution
        }
      }
    `);
    return this.graphQl.execute(document).pipe(map((data) => data.fxInfo));
  }
}
