import { HttpClient, HttpEvent } from '@angular/common/http';
import { DestroyRef, Injectable, effect, inject, signal } from '@angular/core';
import { Observable, map, tap } from 'rxjs';
import { graphql } from '../models/generated';
import { AuthService } from './auth-service';
import { GraphQlService } from './graphql-service';

/** Pole účtenky pro řádek seznamu — bez řádků a obrázků, ty potřebuje jen detail. */
export const receiptListFieldsFragment = graphql(`
  fragment ReceiptListFields on Receipt {
    id
    processingStatus
    reviewStatus
    createdAt
    processedAt
    purchasedAt
    storeNameRaw
    totalAmount
    currency
    store {
      ...StoreFields
    }
    unseen
    imagesDeletedAt
    lineCounts {
      total
      unmatched
      suggested
      matched
      ignored
    }
  }
`);

export const receiptLineFieldsFragment = graphql(`
  fragment ReceiptLineFields on ReceiptLine {
    id
    lineNo
    labelRaw
    internalCode
    quantity
    quantityUnit
    unitPrice
    lineTotal
    discountAmount
    priceKind
    status
    matchSource
    product {
      ...ProductSummaryFields
    }
    observationId
    skipReason
  }
`);

export const receiptDetailFieldsFragment = graphql(`
  fragment ReceiptDetailFields on Receipt {
    ...ReceiptListFields
    storeAddressRaw
    storeIcoRaw
    suggestedStore {
      ...StoreFields
    }
    imagePaths
    lines {
      ...ReceiptLineFields
    }
  }
`);

/** Odpověď REST `POST /api/receipts` (ReceiptController.UploadResponse). */
export interface ReceiptUploadResponse {
  id: string;
}

export interface ReceiptUploadRequest {
  /** Části účtenky shora dolů, už zamaskované a vypálené do JPEG (`receipt-mask.ts`). */
  parts: Blob[];
  consentVersion: number;
  archiveConsent: boolean;
  capturedAt: string | null;
}

/**
 * Účtenky (docs/soukromi.md, „Účtenky") — vidí je výhradně vlastník, takže všechno tady
 * vyžaduje přihlášení. Nahrání jde přes REST multipart (GraphQL multipart v projektu není,
 * stejně jako u `MediaService`), párování přes GraphQL. `tokenInterceptor` přidá Bearer
 * i k REST voláním a token předem obnoví (`AuthService.validAccessToken`) — prošlý token by
 * jinak GraphQL odbavil jako anonymní request a `receipt(id)` by vrátil `null`, jako by účtenka
 * neexistovala.
 *
 * Drží i počet účtenek s novinkou pro odznak v menu (`receiptSummary.unseenCount`). Web nemá
 * push ani WorkManager jako mobil, takže se ptá při přihlášení, při návratu do záložky a po
 * každé akci, která počet mění.
 */
@Injectable({ providedIn: 'root' })
export class ReceiptService {
  private readonly http = inject(HttpClient);
  private readonly graphQl = inject(GraphQlService);
  private readonly auth = inject(AuthService);

  private readonly unseenCountSignal = signal(0);
  private readonly consentVersionSignal = signal<number | null>(null);
  readonly unseenCount = this.unseenCountSignal.asReadonly();
  /** Aktuální verze textu souhlasu ze serveru — `null`, dokud ji appka nezná. */
  readonly consentVersion = this.consentVersionSignal.asReadonly();

  constructor() {
    effect(() => {
      if (this.auth.isLoggedIn()) {
        this.refreshSummary();
      } else {
        this.unseenCountSignal.set(0);
      }
    });
    if (typeof document !== 'undefined') {
      const onVisible = () => {
        if (document.visibilityState === 'visible') this.refreshSummary();
      };
      document.addEventListener('visibilitychange', onVisible);
      inject(DestroyRef).onDestroy(() =>
        document.removeEventListener('visibilitychange', onVisible),
      );
    }
  }

  /** Obnoví odznak; chyba (offline, odhlášení) se tiše přejde — odznak je jen nápověda. */
  refreshSummary(): void {
    if (!this.auth.isLoggedIn()) return;
    this.summary().subscribe({ error: () => {} });
  }

  summary() {
    const document = graphql(`
      query ReceiptSummary {
        receiptSummary {
          unseenCount
          consentVersion
        }
      }
    `);
    return this.graphQl.execute(document).pipe(
      map((data) => data.receiptSummary),
      tap((summary) => {
        this.unseenCountSignal.set(summary.unseenCount);
        this.consentVersionSignal.set(summary.consentVersion);
      }),
    );
  }

  myReceipts(first: number, offset: number) {
    const document = graphql(`
      query MyReceipts($first: Int, $offset: Int) {
        myReceipts(first: $first, offset: $offset) {
          items {
            ...ReceiptListFields
          }
          totalCount
          hasMore
        }
      }
    `);
    return this.graphQl.execute(document, { first, offset }).pipe(map((data) => data.myReceipts));
  }

  /** Cizí i neexistující účtenka je `null` (server existenci neprozrazuje). */
  receipt(id: string) {
    const document = graphql(`
      query Receipt($id: ID!) {
        receipt(id: $id) {
          ...ReceiptDetailFields
        }
      }
    `);
    return this.graphQl.execute(document, { id }).pipe(map((data) => data.receipt));
  }

  /** Bez `query` hledá server podle textu z účtenky v rozsahu obchodu účtenky. */
  lineSuggestions(lineId: string, query: string | null, first = 10) {
    const document = graphql(`
      query ReceiptLineSuggestions($lineId: ID!, $query: String, $first: Int) {
        receiptLineSuggestions(lineId: $lineId, query: $query, first: $first) {
          ...ProductSummaryFields
        }
      }
    `);
    return this.graphQl
      .execute(document, { lineId, query, first })
      .pipe(map((data) => data.receiptLineSuggestions));
  }

  /** Změna obchodu na serveru znovu spustí strojové párování řádků (jiný řetězec = jiné zkratky). */
  updateHeader(id: string, storeId: string | null, purchasedAt: string | null) {
    const document = graphql(`
      mutation UpdateReceiptHeader($id: ID!, $storeId: ID, $purchasedAt: DateTime) {
        updateReceiptHeader(id: $id, storeId: $storeId, purchasedAt: $purchasedAt) {
          ...ReceiptDetailFields
        }
      }
    `);
    return this.graphQl
      .execute(document, { id, storeId, purchasedAt })
      .pipe(map((data) => data.updateReceiptHeader));
  }

  /** Jen připraví řádek k potvrzení (stav SUGGESTED) — cenu zapíše až `confirmLines`. */
  matchLine(lineId: string, productId: string, newProduct = false) {
    const document = graphql(`
      mutation MatchReceiptLine($lineId: ID!, $productId: ID!, $newProduct: Boolean) {
        matchReceiptLine(lineId: $lineId, productId: $productId, newProduct: $newProduct) {
          ...ReceiptLineFields
        }
      }
    `);
    return this.graphQl
      .execute(document, { lineId, productId, newProduct })
      .pipe(map((data) => data.matchReceiptLine));
  }

  ignoreLine(lineId: string) {
    const document = graphql(`
      mutation IgnoreReceiptLine($lineId: ID!) {
        ignoreReceiptLine(lineId: $lineId) {
          ...ReceiptLineFields
        }
      }
    `);
    return this.graphQl.execute(document, { lineId }).pipe(map((data) => data.ignoreReceiptLine));
  }

  unmatchLine(lineId: string) {
    const document = graphql(`
      mutation UnmatchReceiptLine($lineId: ID!) {
        unmatchReceiptLine(lineId: $lineId) {
          ...ReceiptLineFields
        }
      }
    `);
    return this.graphQl.execute(document, { lineId }).pipe(map((data) => data.unmatchReceiptLine));
  }

  /**
   * Zapíše ceny z vybraných řádků — každý řádek zvlášť, kolize jednoho (stejná cena týž den)
   * ostatní neshodí a vrátí se ve `skipped` se `skipReason`.
   */
  confirmLines(receiptId: string, lineIds: string[]) {
    const document = graphql(`
      mutation ConfirmReceiptLines($receiptId: ID!, $lineIds: [ID!]!) {
        confirmReceiptLines(receiptId: $receiptId, lineIds: $lineIds) {
          receipt {
            ...ReceiptDetailFields
          }
          confirmed
          skipped {
            ...ReceiptLineFields
          }
        }
      }
    `);
    return this.graphQl
      .execute(document, { receiptId, lineIds })
      .pipe(map((data) => data.confirmReceiptLines));
  }

  markSeen(id: string) {
    const document = graphql(`
      mutation MarkReceiptSeen($id: ID!) {
        markReceiptSeen(id: $id) {
          id
          unseen
        }
      }
    `);
    return this.graphQl.execute(document, { id }).pipe(
      map((data) => data.markReceiptSeen),
      tap(() => this.refreshSummary()),
    );
  }

  /** Smaže účtenku i s obrázky; už potvrzené ceny zůstávají (docs/soukromi.md). */
  delete(id: string) {
    const document = graphql(`
      mutation DeleteReceipt($id: ID!) {
        deleteReceipt(id: $id)
      }
    `);
    return this.graphQl.execute(document, { id }).pipe(
      map((data) => data.deleteReceipt),
      tap(() => this.refreshSummary()),
    );
  }

  /**
   * `POST /api/receipts` — části v pořadí shora dolů pod stejným jménem `images`. Události
   * nahrávání (`reportProgress`) jdou ven, ať UI může ukázat průběh u delší účtenky.
   */
  upload(request: ReceiptUploadRequest): Observable<HttpEvent<ReceiptUploadResponse>> {
    const formData = new FormData();
    request.parts.forEach((part, index) => formData.append('images', part, `part-${index}.jpg`));
    formData.append('consentVersion', String(request.consentVersion));
    formData.append('archiveConsent', String(request.archiveConsent));
    if (request.capturedAt) formData.append('capturedAt', request.capturedAt);
    return this.http.post<ReceiptUploadResponse>('/api/receipts', formData, {
      reportProgress: true,
      observe: 'events',
    });
  }

  /**
   * Obrázek účtenky jako Blob — endpoint obslouží jen vlastníka (Bearer přidá `tokenInterceptor`),
   * takže `<img src>` na cestu napřímo nejde. Volající si z Blobu udělá object URL a po sobě ho
   * uvolní (`URL.revokeObjectURL`).
   */
  image(path: string): Observable<Blob> {
    return this.http.get(path, { responseType: 'blob' });
  }
}
