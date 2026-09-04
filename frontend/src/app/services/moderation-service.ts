import { Injectable, inject } from '@angular/core';
import { map } from 'rxjs';
import { GraphQlService } from './graphql-service';
import { graphql } from '../models/generated';
import type { RecordType } from '../models/generated/enums';

/**
 * Nástroj pro T4 (docs/reputace.md, "Moderace") — přezkum fronty nahlášených záznamů,
 * zamítání cen a pozastavování účtů. Všech pět operací vyžaduje roli moderátora na serveru
 * (`MODERATION_REQUIRES_ROLE`) — appka gating jen ZOBRAZUJE (`Viewer.moderator`), nevynucuje.
 */
@Injectable({ providedIn: 'root' })
export class ModerationService {
  private readonly graphQl = inject(GraphQlService);

  flaggedRecords(recordType: RecordType | null, first = 20, offset = 0) {
    const document = graphql(`
      query FlaggedRecords($recordType: RecordType, $first: Int, $offset: Int) {
        flaggedRecords(recordType: $recordType, first: $first, offset: $offset) {
          totalCount
          items {
            recordType
            recordId
            flagCount
            firstFlaggedAt
            lastFlaggedAt
            reasons
            hidden
            authorPublicUid
            authorHandle
            product {
              ...ProductSummaryFields
            }
            store {
              ...StoreFields
            }
            photo {
              ...PhotoFields
            }
          }
        }
      }
    `);
    return this.graphQl
      .execute(document, { recordType, first, offset })
      .pipe(map((data) => data.flaggedRecords));
  }

  resolveFlags(recordType: RecordType, recordId: string, resolution: 'DISMISSED' | 'UPHELD') {
    const document = graphql(`
      mutation ResolveFlags(
        $recordType: RecordType!
        $recordId: ID!
        $resolution: FlagResolution!
      ) {
        resolveFlags(recordType: $recordType, recordId: $recordId, resolution: $resolution)
      }
    `);
    return this.graphQl
      .execute(document, { recordType, recordId, resolution })
      .pipe(map((data) => data.resolveFlags));
  }

  mergeProducts(sourceId: string, targetId: string) {
    const document = graphql(`
      mutation MergeProducts($sourceId: ID!, $targetId: ID!) {
        mergeProducts(sourceId: $sourceId, targetId: $targetId) {
          ...ProductSummaryFields
        }
      }
    `);
    return this.graphQl
      .execute(document, { sourceId, targetId })
      .pipe(map((data) => data.mergeProducts));
  }

  moderationObservations(productId: string | null, storeId: string | null, first = 20, offset = 0) {
    const document = graphql(`
      query ModerationObservations($productId: ID, $storeId: ID, $first: Int, $offset: Int) {
        moderationObservations(
          productId: $productId
          storeId: $storeId
          first: $first
          offset: $offset
        ) {
          totalCount
          items {
            authorPublicUid
            authorHandle
            productPublication {
              ...PublicationStatusFields
            }
            observation {
              id
              priceAmount
              currency
              priceKind
              unitPrice
              observedAt
              status
              product {
                ...ProductSummaryFields
              }
              store {
                ...StoreFields
              }
            }
          }
        }
      }
    `);
    return this.graphQl
      .execute(document, { productId, storeId, first, offset })
      .pipe(map((data) => data.moderationObservations));
  }

  setObservationRejected(id: string, rejected: boolean, reason: string | null) {
    const document = graphql(`
      mutation SetObservationRejected($id: ID!, $rejected: Boolean!, $reason: String) {
        setObservationRejected(id: $id, rejected: $rejected, reason: $reason) {
          id
          status
        }
      }
    `);
    return this.graphQl
      .execute(document, { id, rejected, reason })
      .pipe(map((data) => data.setObservationRejected));
  }

  setUserSuspended(publicUid: string, suspended: boolean, reason: string | null) {
    const document = graphql(`
      mutation SetUserSuspended($publicUid: ID!, $suspended: Boolean!, $reason: String) {
        setUserSuspended(publicUid: $publicUid, suspended: $suspended, reason: $reason)
      }
    `);
    return this.graphQl
      .execute(document, { publicUid, suspended, reason })
      .pipe(map((data) => data.setUserSuspended));
  }

  /**
   * Fronta zpětné vazby od uživatelů appky (core.feedback) — handled null vrací obojí.
   * quarantined (výchozí false) odděluje běžnou frontu od záložky "Podezřelé"
   * (FeedbackSpamDetector, docs/nasazeni.md "obrana proti spamu").
   */
  feedbackItems(handled: boolean | null, quarantined: boolean, first = 20, offset = 0) {
    const document = graphql(`
      query FeedbackItems($handled: Boolean, $quarantined: Boolean, $first: Int, $offset: Int) {
        feedbackItems(
          handled: $handled
          quarantined: $quarantined
          first: $first
          offset: $offset
        ) {
          totalCount
          items {
            id
            category
            message
            contactEmail
            clientKind
            appVersion
            platformInfo
            locale
            country
            pageRef
            diagnostics
            createdAt
            handled
            handledNote
            authorPublicUid
            authorHandle
            spamScore
            spamReasons
            quarantined
          }
        }
      }
    `);
    return this.graphQl
      .execute(document, { handled, quarantined, first, offset })
      .pipe(map((data) => data.feedbackItems));
  }

  setFeedbackHandled(id: string, handled: boolean, note: string | null) {
    const document = graphql(`
      mutation SetFeedbackHandled($id: ID!, $handled: Boolean!, $note: String) {
        setFeedbackHandled(id: $id, handled: $handled, note: $note)
      }
    `);
    return this.graphQl
      .execute(document, { id, handled, note })
      .pipe(map((data) => data.setFeedbackHandled));
  }

  /** Cesta zpět z karantény (falešný poplach), stejný princip jako resolveFlags DISMISSED. */
  setFeedbackQuarantined(id: string, quarantined: boolean) {
    const document = graphql(`
      mutation SetFeedbackQuarantined($id: ID!, $quarantined: Boolean!) {
        setFeedbackQuarantined(id: $id, quarantined: $quarantined)
      }
    `);
    return this.graphQl
      .execute(document, { id, quarantined })
      .pipe(map((data) => data.setFeedbackQuarantined));
  }
}
