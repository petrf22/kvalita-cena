import { Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoDirective } from '@jsverse/transloco';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { Subscription } from 'rxjs';
import { ConnectivityService, isNetworkError } from '../../services/connectivity-service';
import { FormatService } from '../../services/format-service';
import { ReceiptService } from '../../services/receipt-service';

interface ImageSlot {
  path: string;
  url: string | null;
  state: 'loading' | 'ready' | 'error' | 'offline';
}

/**
 * Obrázky účtenky v pořadí shora dolů. Endpoint je jen pro vlastníka a posílá
 * `Cache-Control: private, no-store` (docs/soukromi.md), takže `<img src>` na cestu napřímo
 * nejde — obrázek se stáhne s Bearer tokenem jako Blob a ukáže přes object URL, které se při
 * odchodu ze stránky uvolní.
 */
@Component({
  selector: 'app-receipt-images',
  imports: [TranslocoDirective, NzAlertModule, NzButtonModule, NzSpinModule],
  template: `
    <ng-container *transloco="let t; scope: 'receipts'">
      @if (paths().length === 0) {
        <nz-alert
          nzType="info"
          nzShowIcon
          [nzMessage]="t('receipts.images.deleted')"
          [nzDescription]="
            deletedAt() ? t('receipts.images.deletedAt', { date: format.date(deletedAt()) }) : null
          "
        />
      } @else {
        <ol class="images">
          @for (slot of slots(); track slot.path; let i = $index) {
            <li class="image-slot">
              @switch (slot.state) {
                @case ('ready') {
                  <a [href]="slot.url" target="_blank" rel="noopener" class="image-link">
                    <img
                      [src]="slot.url"
                      [alt]="t('receipts.images.partAlt', { index: i + 1, total: slots().length })"
                      class="image"
                    />
                  </a>
                }
                @case ('loading') {
                  <div class="image-placeholder">
                    <nz-spin nzSimple />
                  </div>
                }
                @default {
                  <div class="image-placeholder image-failed">
                    <span>{{
                      slot.state === 'offline'
                        ? t('receipts.images.offline')
                        : t('receipts.images.failed')
                    }}</span>
                    <button nz-button nzSize="small" type="button" (click)="load(i)">
                      {{ t('receipts.retry') }}
                    </button>
                  </div>
                }
              }
            </li>
          }
        </ol>
        <p class="images-hint">{{ t('receipts.images.zoomHint') }}</p>
      }
    </ng-container>
  `,
  styles: `
    .images {
      display: grid;
      gap: var(--kc-space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .image-link {
      display: block;
    }
    .image {
      display: block;
      width: 100%;
      height: auto;
      border: 1px solid var(--kc-border-split);
    }
    /* Rozměr zástupné plochy, ne rozestup — ať se stránka při načítání neskáče o pár pixelů. */
    .image-placeholder {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--kc-space-2);
      min-height: 160px;
      padding: var(--kc-space-4);
      border: 1px dashed var(--kc-border);
      background: var(--kc-bg-subtle);
      text-align: center;
    }
    .image-failed {
      color: var(--kc-text-secondary);
      font-size: var(--kc-font-sm);
    }
    .images-hint {
      margin: var(--kc-space-2) 0 0;
      font-size: var(--kc-font-xs);
      color: var(--kc-text-muted);
    }
  `,
})
export class ReceiptImages {
  private readonly receiptService = inject(ReceiptService);
  private readonly connectivity = inject(ConnectivityService);
  protected readonly format = inject(FormatService);

  readonly paths = input.required<readonly string[]>();
  readonly deletedAt = input<string | null>(null);

  protected readonly slots = signal<ImageSlot[]>([]);
  private readonly requests = new Map<string, Subscription>();

  constructor() {
    // Detail po každé mutaci dostane novou instanci účtenky se stejnými cestami — stahovat
    // obrázky znovu by bylo zbytečné, proto se porovnávají cesty, ne identita pole.
    effect(() => {
      const paths = this.paths();
      untracked(() => {
        const current = this.slots();
        if (current.length === paths.length && current.every((s, i) => s.path === paths[i])) {
          return;
        }
        this.releaseAll();
        this.slots.set(paths.map((path) => ({ path, url: null, state: 'loading' })));
        paths.forEach((_, index) => this.load(index));
      });
    });
    this.connectivity.reconnected$.pipe(takeUntilDestroyed()).subscribe(() =>
      this.slots().forEach((slot, index) => {
        if (slot.state === 'offline' || slot.state === 'error') this.load(index);
      }),
    );
    inject(DestroyRef).onDestroy(() => this.releaseAll());
  }

  protected load(index: number): void {
    const slot = this.slots()[index];
    if (!slot) return;
    this.requests.get(slot.path)?.unsubscribe();
    this.update(index, { state: 'loading' });
    const request = this.receiptService.image(slot.path).subscribe({
      next: (blob) => this.update(index, { state: 'ready', url: URL.createObjectURL(blob) }),
      error: (err: unknown) =>
        this.update(index, { state: isNetworkError(err) ? 'offline' : 'error' }),
    });
    this.requests.set(slot.path, request);
  }

  private update(index: number, patch: Partial<ImageSlot>): void {
    this.slots.update((slots) =>
      slots.map((slot, i) => {
        if (i !== index) return slot;
        if (patch.url && slot.url) URL.revokeObjectURL(slot.url);
        return { ...slot, ...patch };
      }),
    );
  }

  private releaseAll(): void {
    this.requests.forEach((request) => request.unsubscribe());
    this.requests.clear();
    for (const slot of this.slots()) if (slot.url) URL.revokeObjectURL(slot.url);
  }
}
