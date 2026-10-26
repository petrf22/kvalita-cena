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
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';
import { NzDatePickerModule } from 'ng-zorro-antd/date-picker';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzPopconfirmModule } from 'ng-zorro-antd/popconfirm';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { Observable } from 'rxjs';
import {
  ConfirmReceiptLinesResult,
  Product,
  ProductSummary,
  ReceiptDetail,
  ReceiptLine,
  Store,
} from '../../models/catalog';
import { QuantityUnit, ReceiptLineStatus } from '../../models/generated/enums';
import { AuthService } from '../../services/auth-service';
import { ConnectivityService, isNetworkError } from '../../services/connectivity-service';
import { FormatService } from '../../services/format-service';
import { ReceiptService } from '../../services/receipt-service';
import {
  QUANTITY_UNIT_KEYS,
  RECEIPT_LINE_STATUS_KEYS,
  RECEIPT_MATCH_SOURCE_KEYS,
} from '../../shared/enum-labels';
import { translateError } from '../../shared/error-message';
import { MoneyPipe } from '../../shared/money.pipe';
import { ProductThumb, usesExternalImageFallback } from '../../shared/product-thumb';
import { StorePicker } from '../../shared/store-picker';
import { ExistingProductMatch, ProductForm } from '../product-form/product-form';
import { loginRedirect, requiresLogin } from './receipt-auth';
import { ReceiptImages } from './receipt-images';
import { ReceiptLineMatch } from './receipt-line-match';
import {
  confirmBlocker,
  defaultSelectedLineIds,
  isAwaitingProcessing,
  isLineConfirmable,
  isLineLocked,
  isProcessingDeadEnd,
  lineSummary,
  nextSelection,
  purchasedAtToDate,
  receiptStatusColor,
  receiptStatusKey,
  receiptStoreName,
  replaceLine,
  samePurchaseTime,
  skipReasonKey,
} from './receipt-status';
import { receiptStorePrefill } from './receipt-store-prefill';

/** Stejná perioda jako seznam (`receipts-page.ts`) — dokud stroj účtenku nezpracoval. */
const PENDING_POLL_MS = 30_000;

type PageState = 'loading' | 'ready' | 'notFound' | 'error' | 'offline';

/**
 * Detail účtenky — párování řádků se zbožím a hromadné potvrzení cen (docs/datovy-model.md,
 * „Účtenky"). Obrázek vlevo, řádky vpravo, na úzkém displeji pod sebou a obrázek sbalený.
 *
 * Cenu nikdy nezapíše stroj ani samotné spárování — jen „Potvrdit spárované" (docs/ai.md,
 * „AI nikdy nerozhoduje"). Potvrzení potřebuje potvrzený obchod a datum a čas nákupu, protože
 * z nich vznikne `storeId` a `observedAt` každé ceny.
 */
@Component({
  selector: 'app-receipt-detail-page',
  imports: [
    FormsModule,
    RouterLink,
    TranslocoDirective,
    TranslocoPipe,
    NzAlertModule,
    NzButtonModule,
    NzCheckboxModule,
    NzDatePickerModule,
    NzIconModule,
    NzModalModule,
    NzPopconfirmModule,
    NzSpinModule,
    NzTagModule,
    MoneyPipe,
    ProductForm,
    ProductThumb,
    ReceiptImages,
    ReceiptLineMatch,
    StorePicker,
  ],
  providers: [provideTranslocoScope('receipts')],
  templateUrl: './receipt-detail-page.html',
  styleUrl: './receipt-detail-page.css',
})
export class ReceiptDetailPage {
  private readonly receiptService = inject(ReceiptService);
  private readonly transloco = inject(TranslocoService);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  protected readonly connectivity = inject(ConnectivityService);
  protected readonly format = inject(FormatService);

  private readonly receiptId = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';

  protected readonly lineStatusKeys = RECEIPT_LINE_STATUS_KEYS;
  protected readonly matchSourceKeys = RECEIPT_MATCH_SOURCE_KEYS;
  protected readonly statusKey = receiptStatusKey;
  protected readonly statusColor = receiptStatusColor;
  protected readonly isLocked = isLineLocked;
  protected readonly isConfirmable = isLineConfirmable;
  protected readonly skipReasonKey = skipReasonKey;

  protected readonly receipt = signal<ReceiptDetail | null>(null);
  protected readonly state = signal<PageState>('loading');
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly storeName = computed(() => {
    const receipt = this.receipt();
    return receipt ? receiptStoreName(receipt) : null;
  });
  /** „Přidat nový obchod" se předvyplní z hlavičky účtenky, ať uživatel neopisuje. */
  protected readonly storePrefill = computed(() => {
    const receipt = this.receipt();
    return receipt ? receiptStorePrefill(receipt) : null;
  });
  protected readonly awaiting = computed(() => {
    const receipt = this.receipt();
    return receipt != null && isAwaitingProcessing(receipt);
  });
  protected readonly deadEnd = computed(() => {
    const receipt = this.receipt();
    return receipt != null && isProcessingDeadEnd(receipt);
  });
  protected readonly summary = computed(() => lineSummary(this.receipt()?.lines ?? []));
  protected readonly showExternalImageNote = computed(() =>
    (this.receipt()?.lines ?? []).some(
      (line) => line.product != null && usesExternalImageFallback(line.product),
    ),
  );

  // --- výběr řádků k potvrzení
  protected readonly selected = signal<Set<string>>(new Set());
  protected readonly selectedCount = computed(
    () => (this.receipt()?.lines ?? []).filter((l) => this.selected().has(l.id)).length,
  );
  protected readonly blocker = computed(() => {
    const receipt = this.receipt();
    return receipt ? confirmBlocker(receipt, this.selectedCount()) : 'notProcessed';
  });

  // --- hlavička
  protected readonly editingStore = signal(false);
  protected readonly pendingStore = signal<Store | null>(null);
  protected readonly purchasedAtDraft = signal<Date | null>(null);
  protected readonly purchasedAtChanged = computed(
    () => !samePurchaseTime(this.purchasedAtDraft(), this.receipt()?.purchasedAt),
  );
  protected readonly savingHeader = signal(false);
  protected readonly headerError = signal<string | null>(null);

  // --- řádky
  protected readonly busyLineId = signal<string | null>(null);
  protected readonly lineErrors = signal<Record<string, string>>({});
  protected readonly matchingLineId = signal<string | null>(null);
  protected readonly createForLine = signal<ReceiptLine | null>(null);

  // --- potvrzení a smazání
  protected readonly confirming = signal(false);
  protected readonly confirmResult = signal<ConfirmReceiptLinesResult | null>(null);
  protected readonly confirmError = signal<string | null>(null);
  protected readonly deleting = signal(false);
  protected readonly deleteError = signal<string | null>(null);

  /** Na úzkém displeji je obrázek sbalený, ať řádky nezačínají až pod pěti částmi účtenky. */
  protected readonly imagesExpanded = signal(false);

  private markedSeen = false;

  /** Formát pro `nz-date-picker` s časem — datum podle jazyka, čas vždy 24h bez sekund. */
  protected readonly dateTimeInputFormat = computed(() => `${this.format.dateInputFormat()} HH:mm`);
  protected readonly isFuture = (date: Date): boolean => date.getTime() > Date.now();

  constructor() {
    this.load();
    this.connectivity.reconnected$.pipe(takeUntilDestroyed()).subscribe(() => {
      if (this.state() !== 'ready') this.load();
    });
    const timer = setInterval(() => {
      if (this.awaiting() && this.connectivity.online() && document.visibilityState === 'visible') {
        this.load(true);
      }
    }, PENDING_POLL_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected load(silent = false): void {
    // Stejně jako seznam: bez sítě požadavek skončí hned a stav určí až odpověď.
    if (!silent) this.state.set('loading');
    this.receiptService.receipt(this.receiptId).subscribe({
      next: (receipt) => {
        if (!receipt) {
          // Anonymní odpověď na prošlou session vypadá stejně jako cizí účtenka (null) —
          // rozhodne až to, jestli appka po pokusu o obnovu tokenu pořád přihlášená je.
          if (!this.auth.isLoggedIn()) {
            loginRedirect(this.router);
            return;
          }
          if (!silent) this.state.set('notFound');
          return;
        }
        this.applyReceipt(receipt, true);
        this.state.set('ready');
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

  /** Nová verze účtenky ze serveru — zaškrtnutí se přepočítá, datum v editoru se srovná. */
  private applyReceipt(receipt: ReceiptDetail, resetDraft: boolean): void {
    const previous = this.receipt();
    this.selected.set(
      previous
        ? nextSelection(this.selected(), previous.lines, receipt.lines)
        : defaultSelectedLineIds(receipt.lines),
    );
    this.receipt.set(receipt);
    if (resetDraft || !previous) this.purchasedAtDraft.set(purchasedAtToDate(receipt.purchasedAt));
    this.markSeenOnce(receipt);
  }

  /**
   * „Viděno" až po zpracování — dřív by se novinka (hotové zpracování) zhasla ještě předtím,
   * než vznikla, a odznak by o hotové účtence nikdy neřekl.
   */
  private markSeenOnce(receipt: ReceiptDetail): void {
    if (this.markedSeen || isAwaitingProcessing(receipt)) return;
    this.markedSeen = true;
    this.receiptService.markSeen(receipt.id).subscribe({ error: () => (this.markedSeen = false) });
  }

  protected storeAddress(store: Store): string {
    return [store.street, store.city].filter(Boolean).join(', ');
  }

  /** Syrový text hlavičky, jak ho přečetl stroj — nápověda, který obchod to byl. */
  protected rawHeader(receipt: ReceiptDetail): string {
    const ico = receipt.storeIcoRaw
      ? this.transloco.translate('receipts.header.rawIco', { ico: receipt.storeIcoRaw })
      : null;
    return [receipt.storeNameRaw, receipt.storeAddressRaw, ico].filter(Boolean).join(' · ');
  }

  // --- výběr

  protected toggle(lineId: string, checked: boolean): void {
    this.selected.update((current) => {
      const next = new Set(current);
      if (checked) next.add(lineId);
      else next.delete(lineId);
      return next;
    });
  }

  // --- hlavička

  protected confirmSuggestedStore(): void {
    const store = this.receipt()?.suggestedStore;
    if (store) this.saveHeader(store.id, null);
  }

  protected startStoreEdit(): void {
    this.pendingStore.set(null);
    this.editingStore.set(true);
  }

  protected saveStore(): void {
    const store = this.pendingStore();
    if (store) this.saveHeader(store.id, null);
  }

  protected savePurchasedAt(): void {
    const date = this.purchasedAtDraft();
    if (date) this.saveHeader(null, date.toISOString());
  }

  private saveHeader(storeId: string | null, purchasedAt: string | null): void {
    const receipt = this.receipt();
    if (!receipt) return;
    this.savingHeader.set(true);
    this.headerError.set(null);
    this.receiptService.updateHeader(receipt.id, storeId, purchasedAt).subscribe({
      next: (updated) => {
        this.savingHeader.set(false);
        this.editingStore.set(false);
        this.pendingStore.set(null);
        this.applyReceipt(updated, purchasedAt !== null);
      },
      error: (err: unknown) => {
        this.savingHeader.set(false);
        this.headerError.set(this.actionErrorMessage(err));
      },
    });
  }

  // --- řádky

  protected quantityText(line: ReceiptLine): string {
    const receipt = this.receipt();
    const unit = this.transloco.translate(QUANTITY_UNIT_KEYS[line.quantityUnit]);
    const price =
      line.unitPrice != null ? this.format.money(line.unitPrice, receipt?.currency) : null;
    const quantity = line.quantity != null ? this.format.number(line.quantity) : null;
    // U váhového zboží je jednotková cena za kg/l, ne zaplacená částka (docs/datovy-model.md).
    const weighed = line.quantityUnit !== QuantityUnit.Pcs;
    if (quantity && price) {
      return this.transloco.translate(
        weighed ? 'receipts.line.quantityWeighed' : 'receipts.line.quantityPieces',
        { quantity, unit, price },
      );
    }
    if (price) {
      return weighed
        ? this.transloco.translate('receipts.line.pricePerUnit', { price, unit })
        : price;
    }
    return quantity ? `${quantity} ${unit}` : '';
  }

  protected lineColor(line: ReceiptLine): string {
    switch (line.status) {
      case ReceiptLineStatus.Suggested:
        return 'processing';
      case ReceiptLineStatus.Matched:
        return 'success';
      default:
        return 'default';
    }
  }

  protected openMatch(line: ReceiptLine): void {
    this.clearLineError(line.id);
    this.matchingLineId.set(this.matchingLineId() === line.id ? null : line.id);
  }

  protected pickProduct(line: ReceiptLine, product: ProductSummary): void {
    this.runLineAction(line, this.receiptService.matchLine(line.id, product.id), () =>
      this.matchingLineId.set(null),
    );
  }

  protected ignore(line: ReceiptLine): void {
    this.runLineAction(line, this.receiptService.ignoreLine(line.id));
  }

  protected unmatch(line: ReceiptLine): void {
    this.runLineAction(line, this.receiptService.unmatchLine(line.id));
  }

  protected openCreate(line: ReceiptLine): void {
    this.matchingLineId.set(null);
    this.createForLine.set(line);
  }

  /** Právě založené zboží — spárovat s příznakem `newProduct` (zdroj NEW_PRODUCT). */
  protected onProductCreated(line: ReceiptLine, product: Product): void {
    this.createForLine.set(null);
    this.runLineAction(line, this.receiptService.matchLine(line.id, product.id, true));
  }

  /** Formulář místo založení nabídl existující zboží a uživatel ho vybral. */
  protected onExistingMatched(line: ReceiptLine, match: ExistingProductMatch): void {
    this.createForLine.set(null);
    this.runLineAction(line, this.receiptService.matchLine(line.id, match.product.id));
  }

  private runLineAction(
    line: ReceiptLine,
    action: Observable<ReceiptLine>,
    onSuccess?: () => void,
  ): void {
    this.busyLineId.set(line.id);
    this.clearLineError(line.id);
    this.confirmResult.set(null);
    action.subscribe({
      next: (updated) => {
        this.busyLineId.set(null);
        const receipt = this.receipt();
        if (receipt) this.applyReceipt(replaceLine(receipt, updated), false);
        onSuccess?.();
      },
      error: (err: unknown) => {
        this.busyLineId.set(null);
        this.lineErrors.update((errors) => ({
          ...errors,
          [line.id]: this.actionErrorMessage(err),
        }));
      },
    });
  }

  private clearLineError(lineId: string): void {
    this.lineErrors.update((errors) => {
      const { [lineId]: _removed, ...rest } = errors;
      return rest;
    });
  }

  // --- potvrzení

  protected confirm(): void {
    const receipt = this.receipt();
    if (!receipt || this.blocker() !== null || this.confirming()) return;
    const lineIds = receipt.lines.filter((l) => this.selected().has(l.id)).map((l) => l.id);
    this.confirming.set(true);
    this.confirmError.set(null);
    this.confirmResult.set(null);
    this.receiptService.confirmLines(receipt.id, lineIds).subscribe({
      next: (result) => {
        this.confirming.set(false);
        this.confirmResult.set(result);
        this.applyReceipt(result.receipt, false);
        this.receiptService.refreshSummary();
      },
      error: (err: unknown) => {
        this.confirming.set(false);
        this.confirmError.set(this.actionErrorMessage(err));
      },
    });
  }

  // --- smazání

  protected deleteReceipt(): void {
    const receipt = this.receipt();
    if (!receipt) return;
    this.deleting.set(true);
    this.deleteError.set(null);
    this.receiptService.delete(receipt.id).subscribe({
      next: () => void this.router.navigate(['/receipts']),
      error: (err: unknown) => {
        this.deleting.set(false);
        this.deleteError.set(this.actionErrorMessage(err));
      },
    });
  }

  /** Chyba akce: výpadek sítě je offline stav (akce se neuložila), ne „něco se pokazilo". */
  private actionErrorMessage(err: unknown): string {
    if (requiresLogin(err)) {
      loginRedirect(this.router);
      return this.transloco.translate('errors.RECEIPT_REQUIRES_LOGIN');
    }
    if (isNetworkError(err)) return this.transloco.translate('receipts.offlineAction');
    return translateError(err, this.transloco);
  }
}
