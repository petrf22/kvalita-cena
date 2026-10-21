import { HttpErrorResponse, HttpEventType } from '@angular/common/http';
import { Component, DestroyRef, computed, inject, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoDirective, TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzProgressModule } from 'ng-zorro-antd/progress';
import { Subscription } from 'rxjs';
import { ErrorCode } from '../../models/generated/enums';
import { ConnectivityService, isNetworkError } from '../../services/connectivity-service';
import { ReceiptService, ReceiptUploadRequest } from '../../services/receipt-service';
import { translateError } from '../../shared/error-message';
import {
  RECEIPT_MAX_PARTS,
  UnsupportedImageError,
  loadReceiptPart,
  renderMaskedJpeg,
} from './receipt-image';
import { consentOutdated, RECEIPT_CONSENT_VERSION } from './receipt-status';
import { MaskRect, ReceiptPart, movePart, withDefaultBand } from './receipt-mask';
import { ReceiptMaskEditor } from './receipt-mask-editor';

/** Proč odeslání zatím nejde — klíč `receipts.upload.blocker.*`. */
type UploadBlocker = 'noParts' | 'loading' | 'consentOutdated' | 'noConsent' | 'noConsentVersion';

/**
 * Nahrání účtenky z webu: výběr fotek (víc částí dlouhé účtenky shora dolů), jejich pořadí,
 * zakrytí citlivých údajů a souhlas (docs/soukromi.md, „Účtenky"). Masky se vypálí do pixelů
 * na canvasu v prohlížeči (`renderMaskedJpeg`) — na server odejde už jen zakrytý JPEG.
 *
 * Bez zaškrtnutého souhlasu nic neodejde. Posílá se verze textu, který web sám ukazuje
 * (`RECEIPT_CONSENT_VERSION`); když server chce jinou (`receiptSummary.consentVersion`, změna
 * zpracovatele), odeslání zablokuje a řekne, ať se stránka obnoví.
 *
 * Offline: připravená účtenka (už zamaskované JPEGy) zůstane v paměti stránky a odešle se sama
 * po připojení (`ConnectivityService.reconnected$`) — obrazovka to řekne, netváří se jako chyba.
 */
@Component({
  selector: 'app-receipt-upload',
  imports: [
    FormsModule,
    RouterLink,
    TranslocoDirective,
    TranslocoPipe,
    NzAlertModule,
    NzButtonModule,
    NzCheckboxModule,
    NzIconModule,
    NzProgressModule,
    ReceiptMaskEditor,
  ],
  templateUrl: './receipt-upload.html',
  styleUrl: './receipt-upload.css',
})
export class ReceiptUpload {
  private readonly receiptService = inject(ReceiptService);
  private readonly transloco = inject(TranslocoService);
  protected readonly connectivity = inject(ConnectivityService);

  readonly uploaded = output<string>();
  readonly cancelled = output<void>();

  protected readonly maxParts = RECEIPT_MAX_PARTS;
  protected readonly parts = signal<ReceiptPart[]>([]);
  protected readonly selectedId = signal<number | null>(null);
  protected readonly selectedPart = computed(
    () => this.parts().find((part) => part.id === this.selectedId()) ?? null,
  );
  protected readonly selectedIndex = computed(() =>
    this.parts().findIndex((part) => part.id === this.selectedId()),
  );
  protected readonly loadingCount = signal(0);
  protected readonly fileError = signal<string | null>(null);

  protected readonly consent = signal(false);
  protected readonly archiveConsent = signal(false);
  protected readonly consentVersion = this.receiptService.consentVersion;
  protected readonly consentVersionError = signal(false);

  protected readonly sending = signal(false);
  /** Průběh nahrávání v procentech; `null`, dokud prohlížeč velikost nehlásí. */
  protected readonly progress = signal<number | null>(null);
  protected readonly sendError = signal<string | null>(null);
  /** Účtenka je připravená a čeká na připojení (offline). */
  protected readonly waitingForNetwork = signal(false);

  protected readonly blocker = computed<UploadBlocker | null>(() => {
    if (this.parts().length === 0) return 'noParts';
    if (this.loadingCount() > 0) return 'loading';
    if (consentOutdated(this.consentVersion())) return 'consentOutdated';
    if (!this.consent()) return 'noConsent';
    if (this.consentVersion() === null) return 'noConsentVersion';
    return null;
  });

  private nextId = 1;
  private pending: ReceiptUploadRequest | null = null;
  private uploadSubscription: Subscription | null = null;

  constructor() {
    this.loadConsentVersion();
    this.connectivity.reconnected$.pipe(takeUntilDestroyed()).subscribe(() => {
      if (this.pending) this.send(this.pending);
      if (this.consentVersion() === null) this.loadConsentVersion();
    });
    inject(DestroyRef).onDestroy(() => {
      this.uploadSubscription?.unsubscribe();
      for (const part of this.parts()) URL.revokeObjectURL(part.url);
    });
  }

  protected loadConsentVersion(): void {
    this.consentVersionError.set(false);
    this.receiptService.summary().subscribe({ error: () => this.consentVersionError.set(true) });
  }

  protected async onFilesSelected(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = ''; // stejný soubor půjde vybrat znovu (třeba po odebrání)
    if (files.length === 0) return;
    this.fileError.set(null);

    const room = this.maxParts - this.parts().length;
    if (files.length > room) {
      this.fileError.set(
        this.transloco.translate('receipts.upload.tooManyParts', { max: this.maxParts }),
      );
    }
    // Pořadí výběru = pořadí částí shora dolů; načítá se postupně, ať se nesbíhá víc velkých
    // fotek v paměti najednou.
    for (const file of files.slice(0, Math.max(0, room))) {
      this.loadingCount.update((count) => count + 1);
      try {
        const part = await loadReceiptPart(file, this.nextId++);
        this.parts.update((parts) => withDefaultBand([...parts, part]));
        if (this.selectedId() === null) this.selectedId.set(part.id);
      } catch (err) {
        this.fileError.set(
          this.transloco.translate(
            err instanceof UnsupportedImageError
              ? 'receipts.upload.unsupportedFile'
              : 'receipts.upload.fileFailed',
            { name: file.name },
          ),
        );
      } finally {
        this.loadingCount.update((count) => count - 1);
      }
    }
  }

  protected selectPart(id: number): void {
    this.selectedId.set(id);
  }

  protected move(id: number, direction: -1 | 1): void {
    this.parts.update((parts) => withDefaultBand(movePart(parts, id, direction)));
  }

  protected remove(id: number): void {
    const removed = this.parts().find((part) => part.id === id);
    if (removed) URL.revokeObjectURL(removed.url);
    this.parts.update((parts) => withDefaultBand(parts.filter((part) => part.id !== id)));
    if (this.selectedId() === id) this.selectedId.set(this.parts()[0]?.id ?? null);
  }

  /** Jakýkoli zásah uživatele do masek téhle části — výchozí pás se jí pak už nepřidává sám. */
  protected onMasksChange(id: number, masks: MaskRect[]): void {
    this.parts.update((parts) =>
      parts.map((part) =>
        part.id === id
          ? { ...part, masks: masks.map(({ auto: _auto, ...rest }) => rest), masksTouched: true }
          : part,
      ),
    );
  }

  protected maskCount(part: ReceiptPart): number {
    return part.masks.length;
  }

  protected async submit(): Promise<void> {
    if (this.blocker() !== null || this.sending()) return;
    this.sending.set(true);
    this.sendError.set(null);
    this.progress.set(null);
    let blobs: Blob[];
    try {
      blobs = await Promise.all(this.parts().map((part) => renderMaskedJpeg(part)));
    } catch {
      this.sending.set(false);
      this.sendError.set(this.transloco.translate('receipts.upload.renderFailed'));
      return;
    }
    this.send({
      parts: blobs,
      consentVersion: RECEIPT_CONSENT_VERSION,
      archiveConsent: this.archiveConsent(),
      capturedAt: null,
    });
  }

  protected cancelPending(): void {
    this.pending = null;
    this.waitingForNetwork.set(false);
    this.sending.set(false);
  }

  private send(request: ReceiptUploadRequest): void {
    if (!this.connectivity.online()) {
      this.queue(request);
      return;
    }
    this.pending = null;
    this.waitingForNetwork.set(false);
    this.sending.set(true);
    this.uploadSubscription?.unsubscribe();
    this.uploadSubscription = this.receiptService.upload(request).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          this.progress.set(Math.round((event.loaded / event.total) * 100));
        } else if (event.type === HttpEventType.Response && event.body) {
          this.sending.set(false);
          this.receiptService.refreshSummary();
          this.reset();
          this.uploaded.emit(String(event.body.id));
        }
      },
      error: (err: unknown) => {
        if (isNetworkError(err)) {
          this.queue(request);
          return;
        }
        this.sending.set(false);
        this.sendError.set(this.uploadErrorMessage(err));
      },
    });
  }

  private queue(request: ReceiptUploadRequest): void {
    this.pending = request;
    this.waitingForNetwork.set(true);
    this.sending.set(false);
  }

  private uploadErrorMessage(err: unknown): string {
    if (err instanceof HttpErrorResponse) {
      // Součet dílů nad max-request-size — server vrací RECEIPT_UPLOAD_TOO_LARGE, proxy před ním
      // ale holé 413 bez kódu, proto rozhoduje stav, ne kód.
      if (err.status === 413) return this.transloco.translate('receipts.upload.tooLarge');
      const code = (err.error as { code?: string } | null)?.code;
      if (code === ErrorCode.ReceiptConsentRequired) {
        // Text souhlasu se na serveru změnil — zaškrtnutí staré verze už neplatí.
        this.consent.set(false);
        this.loadConsentVersion();
      }
    }
    return translateError(err, this.transloco);
  }

  private reset(): void {
    for (const part of this.parts()) URL.revokeObjectURL(part.url);
    this.parts.set([]);
    this.selectedId.set(null);
    this.consent.set(false);
    this.archiveConsent.set(false);
    this.progress.set(null);
  }
}
