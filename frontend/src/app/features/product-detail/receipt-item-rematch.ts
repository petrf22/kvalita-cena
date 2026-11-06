import {
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { TranslocoDirective, TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { Subscription } from 'rxjs';
import { Product, ProductSummary, RematchReceiptItemResult } from '../../models/catalog';
import { ConnectivityService, isNetworkError } from '../../services/connectivity-service';
import { ProductService } from '../../services/product-service';
import { translateError } from '../../shared/error-message';
import { ProductThumb } from '../../shared/product-thumb';
import { ReceiptItemTag, isReceiptItem } from '../../shared/receipt-item-tag';
import {
  rematchCandidates,
  rematchInitialQuery,
  rematchScopeName,
  rematchStoreId,
} from './receipt-item-rematch-rules';

/** Stejný debounce jako našeptávač řádku účtenky (`receipt-line-match.ts`). */
const SEARCH_DEBOUNCE_MS = 300;
/** Víc než u řádku účtenky — část nabídky může vypadnout jako zboží mimo rozsah obchodu. */
const SUGGESTION_LIMIT = 20;

export interface ReceiptItemRematched {
  result: RematchReceiptItemResult;
  target: ProductSummary;
}

type SearchState = 'loading' | 'ready' | 'error' | 'offline';

/**
 * Dopárování „zboží z účtenky" na skutečné zboží (`rematchReceiptItem`, obsah modálu na detailu
 * zboží). Nabídka jde přes `productSuggestions(name, storeId)` — stejné podobnostní hledání jako
 * při zápisu ceny, omezené na obchod zboží z účtenky; `receiptLineSuggestions` tu nejde, ta
 * potřebuje řádek účtenky. Výběr se ještě jednou potvrdí: vlastní ceny se přesunou hned a cizí
 * po hlasech dalších lidí, takže omyl jedním klikem nejde vzít zpátky.
 *
 * Komponenta hlásí jen výsledek (`rematched`); co dál (přechod na cílové zboží, upozornění),
 * rozhoduje detail zboží.
 */
@Component({
  selector: 'app-receipt-item-rematch',
  imports: [
    FormsModule,
    TranslocoDirective,
    TranslocoPipe,
    NzButtonModule,
    NzIconModule,
    NzInputModule,
    NzSpinModule,
    ProductThumb,
    ReceiptItemTag,
  ],
  templateUrl: './receipt-item-rematch.html',
  styleUrl: './receipt-item-rematch.css',
})
export class ReceiptItemRematch {
  private readonly productService = inject(ProductService);
  private readonly transloco = inject(TranslocoService);
  protected readonly connectivity = inject(ConnectivityService);

  readonly product = input.required<Product>();
  readonly rematched = output<ReceiptItemRematched>();
  readonly cancelled = output<void>();

  protected readonly isReceiptItem = isReceiptItem;

  protected readonly query = signal('');
  protected readonly results = signal<ProductSummary[]>([]);
  protected readonly searchState = signal<SearchState>('loading');
  protected readonly searchError = signal<string | null>(null);

  protected readonly picked = signal<ProductSummary | null>(null);
  protected readonly submitting = signal(false);
  protected readonly submitError = signal<string | null>(null);

  protected readonly scopeName = computed(() => rematchScopeName(this.product()));

  private timer?: ReturnType<typeof setTimeout>;
  private request: Subscription | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      clearTimeout(this.timer);
      this.request?.unsubscribe();
    });
    // Výchozí dotaz je název zboží z účtenky bez gramáže — uživatel hned vidí podobné zboží
    // a jen vybere.
    effect(() => {
      const name = this.product().name;
      untracked(() => {
        this.query.set(rematchInitialQuery(name));
        this.picked.set(null);
        this.search();
      });
    });
    this.connectivity.reconnected$.pipe(takeUntilDestroyed()).subscribe(() => {
      if (this.searchState() === 'offline') this.search();
    });
  }

  protected onQueryChange(value: string): void {
    this.query.set(value);
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.search(), SEARCH_DEBOUNCE_MS);
  }

  protected search(): void {
    clearTimeout(this.timer);
    this.request?.unsubscribe();
    const source = this.product();
    const name = this.query().trim();
    const storeId = rematchStoreId(source);
    // Bez názvu i bez obchodu server vrací prázdno — není se na co ptát.
    if (!name && !storeId) {
      this.results.set([]);
      this.searchState.set('ready');
      return;
    }
    this.searchState.set('loading');
    this.request = this.productService.suggestions(name, storeId, SUGGESTION_LIMIT).subscribe({
      next: (products) => {
        this.results.set(rematchCandidates(source, products));
        this.searchState.set('ready');
      },
      error: (err: unknown) => {
        if (isNetworkError(err)) {
          this.searchState.set('offline');
          return;
        }
        this.searchError.set(translateError(err, this.transloco));
        this.searchState.set('error');
      },
    });
  }

  protected pick(target: ProductSummary): void {
    this.submitError.set(null);
    this.picked.set(target);
  }

  protected backToList(): void {
    this.submitError.set(null);
    this.picked.set(null);
  }

  protected submit(target: ProductSummary): void {
    if (this.submitting()) return;
    this.submitting.set(true);
    this.submitError.set(null);
    this.productService.rematchReceiptItem(this.product().id, target.id).subscribe({
      next: (result) => {
        this.submitting.set(false);
        this.rematched.emit({ result, target });
      },
      error: (err: unknown) => {
        this.submitting.set(false);
        this.submitError.set(
          isNetworkError(err)
            ? this.transloco.translate('product-detail.rematch.offlineAction')
            : translateError(err, this.transloco),
        );
      },
    });
  }

  /** Značka · kategorie · rozsah — podle toho se pozná, které „Mléko" je které. */
  protected meta(product: ProductSummary): string {
    const scope =
      product.catalogScope === 'CHAIN'
        ? product.scopeChain?.name
        : product.catalogScope === 'STORE'
          ? product.scopeStore?.name
          : null;
    return [product.brand?.name, product.category.name, scope].filter(Boolean).join(' · ');
  }
}
