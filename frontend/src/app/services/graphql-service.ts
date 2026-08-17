import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, of, switchMap, throwError } from 'rxjs';
import type { TypedDocumentString } from '../models/generated/graphql';
import { AuthService } from './auth-service';

interface GraphQlErrorExtensions {
  code?: string;
  params?: unknown[];
  existingId?: number;
  /** Spring for GraphQL `ErrorType` (`GraphQlExceptionHandler.errorTypeFor` na backendu) — appka
   *  ho čte jen jako obecný signál "vyžadovalo přihlášení a nemám ho" (`executeAttempt` níž), ne
   *  přes konkrétní `code`, protože vypršelý token server od "nikdy nepřihlášen" nerozezná a
   *  mohl by vrátit kterýkoli z několika *_REQUIRES_LOGIN kódů. */
  classification?: string;
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
  private readonly authService = inject(AuthService);

  execute<TResult, TVariables>(
    document: TypedDocumentString<TResult, TVariables>,
    variables?: TVariables,
  ): Observable<TResult> {
    return this.executeAttempt(document, variables, true);
  }

  /**
   * [allowRecovery] = false na opakovaném pokusu po `AuthService.recoverFromUnauthorized` —
   * jinak by nekonečně zkoušel refresh dokola, kdyby UNAUTHORIZED přišlo i s čerstvým tokenem
   * (např. účet mezitím zanikl).
   */
  private executeAttempt<TResult, TVariables>(
    document: TypedDocumentString<TResult, TVariables>,
    variables: TVariables | undefined,
    allowRecovery: boolean,
  ): Observable<TResult> {
    const hadToken = this.authService.accessToken() !== null;
    return this.http
      .post<GraphQlResponse<TResult>>('/graphql', { query: document.toString(), variables })
      .pipe(
        switchMap((response) => {
          if (response.errors?.length) {
            const first = response.errors[0];
            // Vypršelý/neplatný access token vypadá pro server stejně jako "nikdy nepřihlášen"
            // — reagujeme proto na klasifikaci chyby, ne na konkrétní *_REQUIRES_LOGIN kód, aby
            // recovery fungovala pro libovolný chráněný dotaz.
            if (hadToken && allowRecovery && first.extensions?.classification === 'UNAUTHORIZED') {
              return this.authService.recoverFromUnauthorized().pipe(
                switchMap((recovered) =>
                  recovered
                    ? this.executeAttempt(document, variables, false)
                    : throwError(() => this.toAppError(response.errors!)),
                ),
              );
            }
            return throwError(() => this.toAppError(response.errors!));
          }
          return of(response.data as TResult);
        }),
      );
  }

  private toAppError(errors: GraphQlError[]): GraphQlAppError {
    const first = errors[0];
    return new GraphQlAppError(
      first.extensions?.code ?? null,
      first.extensions?.params ?? [],
      errors.map((e) => e.message).join('; '),
      first.extensions?.existingId ?? null,
    );
  }
}
