import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import type { TypedDocumentString } from '../models/generated/graphql';

interface GraphQlError {
  message: string;
}

interface GraphQlResponse<T> {
  data: T | null;
  errors?: GraphQlError[];
}

/**
 * Bez Apollo v runtime — appka je malá a jeden endpoint na POST /graphql stačí (viz CLAUDE.md,
 * konvence "bez Apollo"). Typy a tvary dotazů generuje graphql-codegen ze schema.graphqls do
 * models/generated (`npm run codegen`) — `document.toString()` pošle stejný GraphQL string
 * jako dřív, jen `execute` už nepotřebuje ruční `as T` cast. Cache ani normalizace grafu se
 * zatím neřeší.
 */
@Injectable({ providedIn: 'root' })
export class GraphQlService {
  private readonly http = inject(HttpClient);

  execute<TResult, TVariables>(
    document: TypedDocumentString<TResult, TVariables>,
    variables?: TVariables,
  ): Observable<TResult> {
    return this.http
      .post<GraphQlResponse<TResult>>('/graphql', { query: document.toString(), variables })
      .pipe(
        map((response) => {
          if (response.errors?.length) {
            throw new Error(response.errors.map((e) => e.message).join('; '));
          }
          return response.data as TResult;
        }),
      );
  }
}
