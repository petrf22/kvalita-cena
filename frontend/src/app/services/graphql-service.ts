import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

interface GraphQlError {
  message: string;
}

interface GraphQlResponse<T> {
  data: T | null;
  errors?: GraphQlError[];
}

/**
 * Bez Apollo — appka je malá a jeden endpoint na POST /graphql stačí (viz CLAUDE.md,
 * konvence "bez Apollo"). Cache ani normalizace grafu se zatím neřeší.
 */
@Injectable({ providedIn: 'root' })
export class GraphQlService {
  private readonly http = inject(HttpClient);

  execute<T>(query: string, variables?: Record<string, unknown>): Observable<T> {
    return this.http.post<GraphQlResponse<T>>('/graphql', { query, variables }).pipe(
      map((response) => {
        if (response.errors?.length) {
          throw new Error(response.errors.map((e) => e.message).join('; '));
        }
        return response.data as T;
      }),
    );
  }
}
