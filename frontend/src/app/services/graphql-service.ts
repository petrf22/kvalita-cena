import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import type { TypedDocumentString } from '../models/generated/graphql';

interface GraphQlErrorExtensions {
  code?: string;
  params?: unknown[];
  existingId?: number;
}

interface GraphQlError {
  message: string;
  extensions?: GraphQlErrorExtensions;
}

interface GraphQlResponse<T> {
  data: T | null;
  errors?: GraphQlError[];
}

/**
 * Chyba z GraphQL API — nese strojový `code`/`params` z `extensions` vedle lokalizované
 * `serverMessage` (docs/lokalizace.md, kontrakt chyby). `translateError` (shared/error-message.ts)
 * podle `code` hledá vlastní překlad; když ho nezná, ukáže `serverMessage`, kterou backend
 * už poslal podle `Accept-Language`.
 */
export class GraphQlAppError extends Error {
  constructor(
    readonly code: string | null,
    readonly params: readonly unknown[],
    readonly serverMessage: string,
    readonly existingId: number | null = null,
  ) {
    super(serverMessage);
  }
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
            const first = response.errors[0];
            throw new GraphQlAppError(
              first.extensions?.code ?? null,
              first.extensions?.params ?? [],
              response.errors.map((e) => e.message).join('; '),
              first.extensions?.existingId ?? null,
            );
          }
          return response.data as TResult;
        }),
      );
  }
}
