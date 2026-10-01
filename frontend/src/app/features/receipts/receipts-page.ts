import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import {
  TranslocoDirective,
  TranslocoPipe,
  TranslocoService,
  provideTranslocoScope,
} from '@jsverse/transloco';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzBadgeModule } from 'ng-zorro-antd/badge';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzPaginationModule } from 'ng-zorro-antd/pagination';
import { NzPopconfirmModule } from 'ng-zorro-antd/popconfirm';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { ReceiptListItem } from '../../models/catalog';
import { ConnectivityService, isNetworkError } from '../../services/connectivity-service';
import { FormatService } from '../../services/format-service';
import { ReceiptService } from '../../services/receipt-service';
import { translateError } from '../../shared/error-message';
import { MoneyPipe } from '../../shared/money.pipe';
import { requiresLogin, loginRedirect } from './receipt-auth';
import {
  isAwaitingProcessing,
  receiptStatusColor,
  receiptStatusKey,
  receiptStoreName,
} from './receipt-status';
import { ReceiptUpload } from './receipt-upload';

const PAGE_SIZE = 20;
/**
 * Jak často se seznam sám obnoví, dokud některá účtenka čeká na zpracování — worker běží na
 * počítači provozovatele a výsledek přichází bez upozornění (web nemá push, docs/soukromi.md).
 */
const PENDING_POLL_MS = 30_000;

type ListState = 'loading' | 'ready' | 'error' | 'offline';

/**
 * „Účtenky" — seznam vlastních naskenovaných účtenek a nahrání nové (docs/soukromi.md,
 * „Účtenky": vidí je jen vlastník). Nepřihlášeného pošle na přihlášení už route guard
 * (`func/auth-guard.ts`); tady se řeší jen session, která vyprší za běhu.
 * Mobilní protějšek: mobile ui/receipt/.
 */
@Component({
  selector: 'app-receipts-page',
  imports: [
    RouterLink,
    TranslocoDirective,
    TranslocoPipe,
    NzAlertModule,
    NzBadgeModule,
    NzButtonModule,
    NzCardModule,
    NzIconModule,
    NzPaginationModule,
    NzPopconfirmModule,
    NzSpinModule,
    NzTagModule,
    MoneyPipe,
    ReceiptUpload,
  ],
  providers: [provideTranslocoScope('receipts')],
  templateUrl: './receipts-page.html',
  styleUrl: './receipts-page.css',
})
export class ReceiptsPage {
  private readonly receiptService = inject(ReceiptService);
  private readonly transloco = inject(TranslocoService);
  private readonly router = inject(Router);
  protected readonly connectivity = inject(ConnectivityService);
  protected readonly format = inject(FormatService);

  protected readonly pageSize = PAGE_SIZE;
  protected readonly items = signal<ReceiptListItem[]>([]);
  protected readonly totalCount = signal(0);
  protected readonly pageIndex = signal(1);
  protected readonly state = signal<ListState>('loading');
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly showUpload = signal(false);
  protected readonly justUploaded = signal(false);
  protected readonly deletingId = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);

  protected readonly statusKey = receiptStatusKey;
  protected readonly statusColor = receiptStatusColor;
  protected readonly storeName = receiptStoreName;

  private readonly hasPending = computed(() => this.items().some(isAwaitingProcessing));

  constructor() {
    this.load();
    this.connectivity.reconnected$.pipe(takeUntilDestroyed()).subscribe(() => {
      if (this.state() !== 'ready') this.load();
    });
    const timer = setInterval(() => {
      if (
        this.hasPending() &&
        this.connectivity.online() &&
        document.visibilityState === 'visible'
      ) {
        this.load(true);
      }
    }, PENDING_POLL_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  /** `silent` = obnova na pozadí; nepřepne obrazovku na spinner ani chybu. */
  protected load(silent = false): void {
    // Bez kontroly signálu online předem: požadavek bez sítě skončí hned (status 0) a ruční
    // „Zkusit znovu" tak pozná i zastaralý offline stav.
    if (!silent) this.state.set('loading');
    const offset = (this.pageIndex() - 1) * PAGE_SIZE;
    this.receiptService.myReceipts(PAGE_SIZE, offset).subscribe({
      next: (page) => {
        this.items.set(page.items);
        this.totalCount.set(page.totalCount);
        this.state.set('ready');
        if (silent) this.receiptService.refreshSummary();
      },
      error: (err: unknown) => {
        if (requiresLogin(err)) {
          loginRedirect(this.router);
          return;
        }
        if (silent) return;
        if (isNetworkError(err)) {
          this.state.set('offline');
          return;
        }
        this.errorMessage.set(translateError(err, this.transloco));
        this.state.set('error');
      },
    });
  }

  protected onPageIndexChange(pageIndex: number): void {
    this.pageIndex.set(pageIndex);
    this.load();
  }

  protected openUpload(): void {
    this.justUploaded.set(false);
    this.showUpload.set(true);
  }

  protected onUploaded(): void {
    this.showUpload.set(false);
    this.justUploaded.set(true);
    this.pageIndex.set(1);
    this.load();
  }

  protected delete(receipt: ReceiptListItem): void {
    this.deletingId.set(receipt.id);
    this.actionError.set(null);
    this.receiptService.delete(receipt.id).subscribe({
      next: () => {
        this.deletingId.set(null);
        this.items.update((items) => items.filter((item) => item.id !== receipt.id));
        this.totalCount.update((count) => Math.max(0, count - 1));
        // Poslední položka stránky zmizela — o stránku zpět, ať nezůstane prázdná obrazovka.
        if (this.items().length === 0 && this.pageIndex() > 1) {
          this.pageIndex.update((index) => index - 1);
          this.load();
        }
      },
      error: (err: unknown) => {
        this.deletingId.set(null);
        if (requiresLogin(err)) {
          loginRedirect(this.router);
          return;
        }
        this.actionError.set(
          isNetworkError(err)
            ? this.transloco.translate('receipts.offlineAction')
            : translateError(err, this.transloco),
        );
      },
    });
  }
}
