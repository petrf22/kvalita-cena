import { Injectable, inject } from '@angular/core';
import { map } from 'rxjs';
import { GraphQlService } from './graphql-service';
import { graphql } from '../models/generated';
import { FeedbackCategory } from '../models/catalog';
import { APP_VERSION } from '../version';

export interface FeedbackSubmission {
  category: FeedbackCategory;
  message: string;
  contactEmail?: string | null;
  pageRef?: string | null;
  diagnostics?: string | null;
}

/**
 * Zpětná vazba od uživatele appky (core.feedback) — na rozdíl od `ModerationService.flagRecord`
 * funguje i BEZ přihlášení (docs/nasazeni.md, "Než pozvat první lidi"). Klient/verze appky/IP
 * čte server sám z hlaviček, appka posílá jen appVersion jako doplňkovou informaci (server má
 * navíc i X-Client-Version u mobilu, web ho neposílá).
 */
@Injectable({ providedIn: 'root' })
export class FeedbackService {
  private readonly graphQl = inject(GraphQlService);

  submitFeedback(submission: FeedbackSubmission) {
    const document = graphql(`
      mutation SubmitFeedback($input: FeedbackInput!) {
        submitFeedback(input: $input) {
          id
        }
      }
    `);
    return this.graphQl
      .execute(document, {
        input: {
          category: submission.category,
          message: submission.message,
          contactEmail: submission.contactEmail ?? null,
          pageRef: submission.pageRef ?? null,
          appVersion: APP_VERSION,
          diagnostics: submission.diagnostics ?? null,
        },
      })
      .pipe(map((data) => data.submitFeedback));
  }
}
