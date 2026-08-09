import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { GraphQlService } from './graphql-service';
import { Photo, RecordType } from '../models/catalog';

/**
 * Fotky zboží a provozoven (core.media). Upload jde přes REST multipart (backend
 * MediaController) — GraphQL v tomhle projektu multipart nepodporuje
 * (graphql-multipart-request-spec, viz backend schema.graphqls). Token-interceptor přidá
 * Authorization hlavičku i sem, běží nad všemi HttpClient požadavky, ne jen nad /graphql.
 * Popisek/pořadí a smazání naopak jdou přes GraphQL mutace jako zbytek katalogu.
 */
@Injectable({ providedIn: 'root' })
export class MediaService {
  private readonly http = inject(HttpClient);
  private readonly graphQl = inject(GraphQlService);

  upload(recordType: RecordType, recordId: string, file: File, caption?: string | null): Observable<Photo> {
    const formData = new FormData();
    formData.append('file', file);
    if (caption) {
      formData.append('caption', caption);
    }
    return this.http.post<Photo>(`/api/media/${recordType}/${recordId}`, formData);
  }

  /** Popisek a pořadí (nejnižší sortOrder = hlavní fotka záznamu). Jen autor fotky. */
  update(id: string, caption: string | null, sortOrder: number | null): Observable<Photo> {
    const gql = `
      mutation UpdatePhoto($id: ID!, $caption: String, $sortOrder: Int) {
        updatePhoto(id: $id, caption: $caption, sortOrder: $sortOrder) {
          id url thumbnailUrl width height caption mine hidden attribution
        }
      }
    `;
    return this.graphQl
      .execute<{ updatePhoto: Photo }>(gql, { id, caption, sortOrder })
      .pipe(map((data) => data.updatePhoto));
  }

  /** Smazání vlastní fotky. Jen autor. */
  remove(id: string): Observable<boolean> {
    const gql = `
      mutation DeletePhoto($id: ID!) {
        deletePhoto(id: $id)
      }
    `;
    return this.graphQl.execute<{ deletePhoto: boolean }>(gql, { id }).pipe(map((data) => data.deletePhoto));
  }

  /** Nahlášení fotky jako nevhodné — jedno nahlášení stačí (docs/reputace.md). */
  flag(id: string, reason?: string): Observable<{ flagCount: number; hidden: boolean }> {
    const gql = `
      mutation FlagPhoto($recordId: ID!, $reason: String) {
        flagRecord(recordType: PHOTO, recordId: $recordId, reason: $reason) { flagCount hidden }
      }
    `;
    return this.graphQl
      .execute<{ flagRecord: { flagCount: number; hidden: boolean } }>(gql, { recordId: id, reason: reason ?? null })
      .pipe(map((data) => data.flagRecord));
  }
}
