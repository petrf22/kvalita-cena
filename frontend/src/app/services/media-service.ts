import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { map } from 'rxjs';
import { GraphQlService } from './graphql-service';
import { graphql } from '../models/generated';
import type { PhotoFieldsFragment } from '../models/generated/graphql';
import type { RecordType } from '../models/generated/enums';

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

  upload(recordType: RecordType, recordId: string, file: File, caption?: string | null) {
    const formData = new FormData();
    formData.append('file', file);
    if (caption) {
      formData.append('caption', caption);
    }
    return this.http.post<PhotoFieldsFragment>(`/api/media/${recordType}/${recordId}`, formData);
  }

  /**
   * Avatar profilu — na rozdíl od {@link upload} vždy NAHRADÍ předchozí avatar (recordId se na
   * backendu bere z Authentication, ne z URL, viz MediaController.uploadAvatar).
   */
  uploadAvatar(file: File) {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<PhotoFieldsFragment>('/api/media/user/avatar', formData);
  }

  /** Popisek a pořadí (nejnižší sortOrder = hlavní fotka záznamu). Jen autor fotky. */
  update(id: string, caption: string | null, sortOrder: number | null) {
    const document = graphql(`
      mutation UpdatePhoto($id: ID!, $caption: String, $sortOrder: Int) {
        updatePhoto(id: $id, caption: $caption, sortOrder: $sortOrder) {
          ...PhotoFields
        }
      }
    `);
    return this.graphQl
      .execute(document, { id, caption, sortOrder })
      .pipe(map((data) => data.updatePhoto));
  }

  /** Smazání vlastní fotky. Jen autor. */
  remove(id: string) {
    const document = graphql(`
      mutation DeletePhoto($id: ID!) {
        deletePhoto(id: $id)
      }
    `);
    return this.graphQl.execute(document, { id }).pipe(map((data) => data.deletePhoto));
  }

  /** Nahlášení fotky jako nevhodné — jedno nahlášení stačí (docs/reputace.md). */
  flag(id: string, reason?: string) {
    const document = graphql(`
      mutation FlagPhoto($recordId: ID!, $reason: String) {
        flagRecord(recordType: PHOTO, recordId: $recordId, reason: $reason) {
          flagCount
          hidden
        }
      }
    `);
    return this.graphQl
      .execute(document, { recordId: id, reason: reason ?? null })
      .pipe(map((data) => data.flagRecord));
  }
}
