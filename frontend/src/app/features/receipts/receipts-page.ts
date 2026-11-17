import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
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
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { Subscription } from 'rxjs';
import { ReceiptFilterCounts, ReceiptListItem } from '../../models/catalog';
import { ReceiptListFilter } from '../../models/generated/enums';
import { ConnectivityService, isNetworkError } from '../../services/connectivity-service';
import { FormatService } from '../../services/format-service';
import { ReceiptService } from '../../services/receipt-service';
import { RECEIPT_LIST_FILTER_KEYS } from '../../shared/enum-labels';
import { translateError } from '../../shared/error-message';
import { MoneyPipe } from '../../shared/money.pipe';
import { requiresLogin, loginRedirect } from './receipt-auth';
import {
  RECEIPT_LIST_FILTERS,
  RECEIPT_LIST_FILTER_STATE,
  filterCountsWithout,
  initialReceiptFilter,
  isAwaitingProcessing,
  receiptFilterCount,
  receiptFilterFromParam,
  receiptFilterParam,
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
 *
 * Seznam se filtruje podle skupiny stavu (`ReceiptListFilter`). Volba žije v query parametru
 * `?filter=`, ať ji zachová návrat z detailu a jde sdílet; bez něj platí výchozí volba
 * (`initialReceiptFilter`): „K vyřízení", když na uživatele něco čeká, jinak „Vše".
 * Mobilní protějšek: mobile ui/receipt/.
 */
@Component({
  selector: 'app-receipts-page',
  imports: [
    FormsModule,
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
    NzRadioModule,
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
  private readonly route = inject(ActivatedRoute);
  protected readonly connectivity = inject(ConnectivityService);
  protected readonly format = inject(FormatService);

  protected readonly pageSize = PAGE_SIZE;
  protected readonly items = signal<ReceiptListItem[]>([]);
  protected readonly totalCount = signal(0);
  protected readonly pageIndex = signal(1);
  protected readonly state = signal<ListState>('loading');
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly filters = RECEIPT_LIST_FILTERS;
  protected readonly filterKeys = RECEIPT_LIST_FILTER_KEYS;
  /** Zobrazená skupina — z URL, nebo výchozí volba. */
  protected readonly filter = signal<ReceiptListFilter>(ReceiptListFilter.ToReview);
  /** Počty všech skupin z poslední odpovědi; `null`, dokud žádná nedoběhla (lišta se neukáže). */
  protected readonly counts = signal<ReceiptFilterCounts | null>(null);
  /** Filtr výslovně zvolený v URL; `null` = platí výchozí volba. */
  private readonly urlFilter = signal<ReceiptListFilter | null>(null);
  /** Výchozí volba čeká na první počty ze serveru (dotaz běží s „K vyřízení"). */
  private resolvingDefault = false;
  private loadSubscription: Subscription | null = null;

  protected readonly showUpload = signal(false);
  protected readonly justUploaded = signal(false);
  protected readonly deletingId = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);

  protected readonly statusKey = receiptStatusKey;
  protected readonly statusColor = receiptStatusColor;
  protected readonly storeName = receiptStoreName;

  /** Uživatel nemá jedinou účtenku — místo lišty filtru úvodní prázdný stav s nahráním. */
  protected readonly hasAnyReceipt = computed(() => (this.counts()?.all ?? 0) > 0);
  /** Stav navigace na detail — „Zpět na seznam" vrátí stejný výslovně zvolený filtr. */
  protected readonly detailLinkState = computed(() => {
    const filter = this.urlFilter();
    return filter ? { [RECEIPT_LIST_FILTER_STATE]: receiptFilterParam(filter) } : {};
  });

  // Podle počtů, ne jen zobrazené stránky: ve filtru „K vyřízení" zpracovávaná účtenka vidět
  // není, a právě tam se po doběhnutí workeru objeví.
  private readonly hasPending = computed(
    () => (this.counts()?.processing ?? 0) > 0 || this.items().some(isAwaitingProcessing),
  );

  constructor() {
    // První hodnota přijde hned — tím se seznam poprvé načte.
    this.route.queryParamMap
      .pipe(takeUntilDestroyed())
      .subscribe((params) => this.applyFilterParam(params.get('filter')));
    this.connectivity.reconnected$.pipe(takeUntilDestroyed()).subscribe(() => {
      if (this.state() !== 'ready') this.load();
    });
    const timer = setInterval(() => {
      if (
        this.hasPending() &&
        this.state() !== 'loading' &&
        this.connectivity.online() &&
        document.visibilityState === 'visible'
      ) {
        this.load(true);
      }
    }, PENDING_POLL_MS);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(timer);
      this.loadSubscription?.unsubscribe();
    });
  }

  /** `silent` = obnova na pozadí; nepřepne obrazovku na spinner ani chybu. */
  protected load(silent = false): void {
    // Bez kontroly signálu online předem: požadavek bez sítě skončí hned (status 0) a ruční
    // „Zkusit znovu" tak pozná i zastaralý offline stav.
    if (!silent) this.state.set('loading');
    const filter = this.filter();
    const offset = (this.pageIndex() - 1) * PAGE_SIZE;
    // Novější dotaz (jiný filtr, stránka) ruší starší, ať pozdě doběhlá odpověď nepřepíše seznam.
    this.loadSubscription?.unsubscribe();
    this.loadSubscription = this.receiptService.myReceipts(PAGE_SIZE, offset, filter).subscribe({
      next: (page) => {
        if (this.resolvingDefault) {
          this.resolvingDefault = false;
          const initial = initialReceiptFilter(page.filterCounts);
          if (initial !== filter) {
            this.filter.set(initial);
            this.load(silent);
            return;
          }
        }
        // Stránka za koncem (smazání, změna v jiné záložce) — na poslední, která existuje.
        const lastPage = Math.max(1, Math.ceil(page.totalCount / PAGE_SIZE));
        if (this.pageIndex() > lastPage) {
          this.pageIndex.set(lastPage);
          this.load(silent);
          return;
        }
        this.items.set(page.items);
        this.totalCount.set(page.totalCount);
        this.counts.set(page.filterCounts);
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

  protected filterCount(filter: ReceiptListFilter): number {
    const counts = this.counts();
    return counts ? receiptFilterCount(counts, filter) : 0;
  }

  /** Volba v liště jde přes URL — načte ji `applyFilterParam`, stejně jako odkaz zvenku. */
  protected selectFilter(filter: ReceiptListFilter): void {
    if (filter === this.urlFilter()) {
      // URL už ji nese a navigace na stejnou adresu by nic nespustila.
      this.filter.set(filter);
      this.pageIndex.set(1);
      this.load();
      return;
    }
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { filter: receiptFilterParam(filter) },
      // Přepínání filtru nezakládá historii — „Zpět" vede pryč ze seznamu, ne o volbu zpět.
      replaceUrl: true,
    });
  }

  protected showAll(): void {
    this.selectFilter(ReceiptListFilter.All);
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
    // Nová účtenka čeká na zpracování — v „K vyřízení" ani jiné skupině by vidět nebyla.
    this.showAll();
  }

  protected delete(receipt: ReceiptListItem): void {
    this.deletingId.set(receipt.id);
    this.actionError.set(null);
    this.receiptService.delete(receipt.id).subscribe({
      next: () => {
        this.deletingId.set(null);
        this.items.update((items) => items.filter((item) => item.id !== receipt.id));
        this.totalCount.update((count) => Math.max(0, count - 1));
        this.counts.update((counts) => counts && filterCountsWithout(counts, receipt));
        // Doplní stránku z další a srovná počty se serverem. Vyprázdněná stránka za první
        // se obnoví viditelně — `load` ji posune o stránku zpět, ať nezůstane prázdná obrazovka.
        this.load(this.items().length > 0 || this.pageIndex() === 1);
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

  /** Filtr z URL — platí pro volbu v liště, návrat z detailu i sdílený odkaz. */
  private applyFilterParam(param: string | null): void {
    const filter = receiptFilterFromParam(param);
    this.urlFilter.set(filter);
    // Bez platného filtru v URL se ptá rovnou na „K vyřízení" — v obvyklém případě, kdy něco
    // čeká, tak stačí jeden dotaz; jinak `load` přepne na „Vše" podle vrácených počtů.
    this.resolvingDefault = filter === null;
    this.filter.set(filter ?? ReceiptListFilter.ToReview);
    this.pageIndex.set(1);
    this.load();
  }
}
