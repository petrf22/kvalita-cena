import {
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { Subscription } from 'rxjs';
import { ProductSummary, ReceiptLine } from '../../models/catalog';
import { isNetworkError } from '../../services/connectivity-service';
import { ReceiptService } from '../../services/receipt-service';
import { translateError } from '../../shared/error-message';
import { ProductThumb, usesExternalImageFallback } from '../../shared/product-thumb';

/** Stejný debounce jako našeptávač názvu v `product-form` a `price-entry-page`. */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * Našeptávač zboží pro jeden řádek účtenky (`receiptLineSuggestions`) — bez dotazu hledá server
 * podle textu z účtenky v rozsahu obchodu (nejdřív známá označení, pak podobnost názvu), s
 * dotazem podle toho, co uživatel napíše. Výběr jen připraví řádek k potvrzení, cenu nezapíše.
 */
@Component({
  selector: 'app-receipt-line-match',
  imports: [
    FormsModule,
    TranslocoDirective,
    NzButtonModule,
    NzIconModule,
    NzInputModule,
    NzSpinModule,
    ProductThumb,
  ],
  templateUrl: './receipt-line-match.html',
  styleUrl: './receipt-line-match.css',
})
export class ReceiptLineMatch implements OnInit {
  private readonly receiptService = inject(ReceiptService);
  private readonly transloco = inject(TranslocoService);

  readonly line = input.required<ReceiptLine>();
  /** Založit nové zboží jde jen s potvrzeným obchodem (rozsah katalogu bezkódového zboží). */
  readonly canCreate = input(true);
  readonly busy = input(false);
  readonly picked = output<ProductSummary>();
  readonly createNew = output<void>();
  readonly closed = output<void>();

  protected readonly query = signal('');
  protected readonly results = signal<ProductSummary[]>([]);
  protected readonly state = signal<'loading' | 'ready' | 'error' | 'offline'>('loading');
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly showExternalImageNote = computed(() =>
    this.results().some((item) => usesExternalImageFallback(item)),
  );

  private timer?: ReturnType<typeof setTimeout>;
  private request: Subscription | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      clearTimeout(this.timer);
      this.request?.unsubscribe();
    });
  }

  ngOnInit(): void {
    this.search();
  }

  protected onQueryChange(value: string): void {
    this.query.set(value);
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.search(), SEARCH_DEBOUNCE_MS);
  }

  protected search(): void {
    clearTimeout(this.timer);
    this.request?.unsubscribe();
    this.state.set('loading');
    const query = this.query().trim() || null;
    this.request = this.receiptService.lineSuggestions(this.line().id, query).subscribe({
      next: (products) => {
        this.results.set(products);
        this.state.set('ready');
      },
      error: (err: unknown) => {
        if (isNetworkError(err)) {
          this.state.set('offline');
          return;
        }
        this.errorMessage.set(translateError(err, this.transloco));
        this.state.set('error');
      },
    });
  }
}
