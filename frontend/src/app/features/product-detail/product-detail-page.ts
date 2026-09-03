import { Location } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  TranslocoDirective,
  TranslocoPipe,
  TranslocoService,
  provideTranslocoScope,
} from '@jsverse/transloco';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzDropdownModule } from 'ng-zorro-antd/dropdown';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzPaginationModule } from 'ng-zorro-antd/pagination';
import { NzRateModule } from 'ng-zorro-antd/rate';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { PriceKind, PricePoint, Product, ProductReview } from '../../models/catalog';
import { AuthService } from '../../services/auth-service';
import { FormatService } from '../../services/format-service';
import { NavigationHistoryService } from '../../services/navigation-history-service';
import { ProductService } from '../../services/product-service';
import { translateError } from '../../shared/error-message';
import { NET_CONTENT_UOM_KEYS, PRICE_KIND_KEYS } from '../../shared/enum-labels';
import { MoneyPipe } from '../../shared/money.pipe';
import { PhotoGallery } from '../../shared/photo-gallery';
import { PriceEntryForm } from '../../shared/price-entry-form';
import { QualityBadge } from '../../shared/quality-badge';
import { RelativeDatePipe } from '../../shared/relative-date.pipe';
import {
  MAX_REVIEW_TEXT_LENGTH,
  remainingReviewCharacters,
  reviewTextValidationError,
} from '../../shared/review-validation';
import { ProductForm } from '../product-form/product-form';
import { PriceChart } from './price-chart';

const CHART_RANGES = [7, 30, 90, 365];
const REVIEWS_PAGE_SIZE = 10;

@Component({
  selector: 'app-product-detail-page',
  imports: [
    FormsModule,
    RouterLink,
    NzCardModule,
    NzDropdownModule,
    NzTableModule,
    NzTagModule,
    NzButtonModule,
    NzIconModule,
    NzSelectModule,
    NzRateModule,
    NzAlertModule,
    NzModalModule,
    NzInputModule,
    NzPaginationModule,
    QualityBadge,
    PriceEntryForm,
    PriceChart,
    PhotoGallery,
    ProductForm,
    TranslocoDirective,
    TranslocoPipe,
    MoneyPipe,
    RelativeDatePipe,
  ],
  providers: [provideTranslocoScope('product-detail')],
  templateUrl: './product-detail-page.html',
  styleUrl: './product-detail-page.css',
})
export class ProductDetailPage {
  private readonly route = inject(ActivatedRoute);
  private readonly location = inject(Location);
  private readonly productService = inject(ProductService);
  private readonly transloco = inject(TranslocoService);
  protected readonly auth = inject(AuthService);
  protected readonly format = inject(FormatService);
  protected readonly navigationHistory = inject(NavigationHistoryService);

  protected readonly priceKindKeys = PRICE_KIND_KEYS;
  protected readonly netContentUomKeys = NET_CONTENT_UOM_KEYS;
  protected readonly chartRanges = CHART_RANGES;

  protected readonly product = signal<Product | null>(null);
  protected readonly loading = signal(true);

  protected readonly historyPoints = signal<PricePoint[]>([]);
  protected readonly historyLoading = signal(false);
  protected readonly historyCurrency = signal<string | null>(null);
  /** Vyplněná, jen když appka řadu skutečně přepočítala (X-Display-Currency), viz docs/lokalizace.md. */
  protected readonly historyDisplayCurrency = signal<string | null>(null);
  protected readonly chartCurrency = computed(
    () => this.historyDisplayCurrency() ?? this.historyCurrency(),
  );
  /**
   * Graf jednu řadu nikdy nemíchá napříč měnami — když appka přepočítává, substituují se
   * OBĚ pole (unit i balení) najednou, se svým vlastním denním kurzem (PricePoint.convertedUnitPrice).
   * Chybí-li kurz pro konkrétní den (starší než nejstarší stažený lístek), spadne ten bod zpět
   * na originál — vzácný okrajový případ, ne důvod celou řadu zahodit.
   */
  protected readonly chartPoints = computed<PricePoint[]>(() => {
    const displayCurrency = this.historyDisplayCurrency();
    const points = this.historyPoints();
    if (!displayCurrency) return points;
    return points.map((point) => ({
      ...point,
      unitPrice: point.convertedUnitPrice ?? point.unitPrice,
      priceAmount: point.convertedPriceAmount ?? point.priceAmount,
    }));
  });
  protected readonly selectedDays = signal(90);
  protected readonly selectedPriceKind = signal<PriceKind>('REGULAR');

  protected readonly ratingError = signal<string | null>(null);

  protected readonly editing = signal(false);

  protected readonly flagging = signal(false);
  protected readonly flagMessage = signal<string | null>(null);

  // --- recenze (text k hodnocení) ---
  protected readonly reviews = signal<ProductReview[]>([]);
  protected readonly reviewsTotalCount = signal(0);
  protected readonly reviewsLoginRequired = signal(false);
  protected readonly reviewsLoading = signal(false);
  protected readonly reviewsPageIndex = signal(1); // nz-pagination je 1-based
  protected readonly reviewsPageSize = REVIEWS_PAGE_SIZE;

  protected readonly reviewModalOpen = signal(false);
  protected readonly reviewText = signal('');
  protected readonly reviewSaving = signal(false);
  protected readonly reviewError = signal<string | null>(null);
  protected readonly reviewRemainingChars = computed(() =>
    remainingReviewCharacters(this.reviewText()),
  );

  protected readonly reviewFlaggingId = signal<string | null>(null);
  protected readonly reviewFlagMessage = signal<string | null>(null);

  protected readonly maxReviewTextLength = MAX_REVIEW_TEXT_LENGTH;

  constructor() {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) this.loadProduct(id);
  }

  /** Vrátí na stránku, odkud uživatel přišel (`/my`, hledání, jiný detail) — ne vždy na hledání. */
  protected goBack(): void {
    this.location.back();
  }

  /** Obnoví agregované ceny v tabulce i graf po úspěšném zápisu (`app-price-entry-form`). */
  protected loadProduct(id: string): void {
    this.loading.set(true);
    this.productService.getById(id).subscribe({
      next: (product) => {
        this.product.set(product);
        this.loading.set(false);
        if (product) {
          this.loadHistory();
          this.loadReviews();
        }
      },
      error: () => this.loading.set(false),
    });
  }

  private loadHistory(): void {
    const product = this.product();
    if (!product) return;
    this.historyLoading.set(true);
    this.productService
      .priceHistory(product.id, this.selectedPriceKind(), this.selectedDays())
      .subscribe({
        next: (history) => {
          this.historyPoints.set(history.points);
          this.historyCurrency.set(history.currency);
          this.historyDisplayCurrency.set(history.displayCurrency ?? null);
          this.historyLoading.set(false);
        },
        error: () => {
          this.historyPoints.set([]);
          this.historyLoading.set(false);
        },
      });
  }

  onDaysChange(days: number): void {
    this.selectedDays.set(days);
    this.loadHistory();
  }

  onPriceKindChange(kind: PriceKind): void {
    this.selectedPriceKind.set(kind);
    this.loadHistory();
  }

  // Metoda, ne computed() — translate() není signálově reaktivní, na změnu jazyka reaguje
  // appka přes reRenderOnLangChange (app.config.ts), stejný vzor jako shared/quality-badge.ts.
  protected qualityRateTooltips(): string[] {
    return [1, 2, 3, 4, 5].map((count) =>
      this.transloco.translate('product-detail.qualityRateStars', { count }),
    );
  }

  rate(stars: number): void {
    const product = this.product();
    // nz-rate posílá i 0 při kliku na už vybranou hvězdu s nzAllowClear (tady vypnuté), ale
    // hlídáme to i tady — mazání hodnocení API nepodporuje.
    if (!product || stars < 1) return;

    this.ratingError.set(null);
    this.productService.rateProduct(product.id, stars).subscribe({
      next: (quality) => {
        this.product.set({ ...product, quality, myQualityRating: stars });
      },
      error: () => {
        this.ratingError.set(this.transloco.translate('product-detail.qualityLoginHint'));
      },
    });
  }

  /** Po uložení editace (`app-product-form` v `product()` režimu) — `updateProduct` vrací celý
   *  ProductDetailFields (na rozdíl od updateStore), takže stačí prosté nahrazení stavu. */
  onProductSaved(updated: Product): void {
    this.product.set(updated);
    this.editing.set(false);
  }

  flagProduct(): void {
    const product = this.product();
    if (!product) return;

    this.flagging.set(true);
    this.flagMessage.set(null);
    this.productService.flagProduct(product.id).subscribe({
      next: (result) => {
        this.flagging.set(false);
        this.flagMessage.set(
          this.transloco.translate(
            result.hidden ? 'product-detail.flagSuccessHidden' : 'product-detail.flagSuccess',
          ),
        );
      },
      error: () => {
        this.flagging.set(false);
        this.flagMessage.set(this.transloco.translate('product-detail.flagFailed'));
      },
    });
  }

  // --- recenze (text k hodnocení) ---

  protected loadReviews(): void {
    const product = this.product();
    if (!product) return;

    this.reviewsLoading.set(true);
    const offset = (this.reviewsPageIndex() - 1) * this.reviewsPageSize;
    this.productService.productReviews(product.id, this.reviewsPageSize, offset).subscribe({
      next: (result) => {
        this.reviews.set(result.items);
        this.reviewsTotalCount.set(result.totalCount);
        this.reviewsLoginRequired.set(result.loginRequired);
        this.reviewsLoading.set(false);
      },
      error: () => this.reviewsLoading.set(false),
    });
  }

  protected onReviewsPageChange(pageIndex: number): void {
    this.reviewsPageIndex.set(pageIndex);
    this.loadReviews();
  }

  /** Předvyplní vlastní text, je-li nějaký — tlačítko je viditelné jen po vybrání hvězdiček. */
  protected openReviewModal(): void {
    this.reviewText.set(this.product()?.myReviewText ?? '');
    this.reviewError.set(null);
    this.reviewModalOpen.set(true);
  }

  protected closeReviewModal(): void {
    this.reviewModalOpen.set(false);
  }

  protected saveReviewText(): void {
    const product = this.product();
    if (!product) return;

    const text = this.reviewText();
    const validationError = reviewTextValidationError(text);
    if (validationError) {
      this.reviewError.set(this.transloco.translate(`errors.${validationError}`));
      return;
    }

    this.reviewSaving.set(true);
    this.reviewError.set(null);
    this.productService.saveProductReviewText(product.id, text.trim()).subscribe({
      next: (result) => {
        this.reviewSaving.set(false);
        this.product.set({ ...product, myReviewText: result.text ?? null });
        this.reviewModalOpen.set(false);
        this.reviewsPageIndex.set(1);
        this.loadReviews();
      },
      error: (err: unknown) => {
        this.reviewSaving.set(false);
        this.reviewError.set(translateError(err, this.transloco));
      },
    });
  }

  protected deleteReviewText(): void {
    const product = this.product();
    if (!product) return;

    this.reviewSaving.set(true);
    this.reviewError.set(null);
    this.productService.deleteProductReviewText(product.id).subscribe({
      next: () => {
        this.reviewSaving.set(false);
        this.product.set({ ...product, myReviewText: null });
        this.reviewModalOpen.set(false);
        this.reviewsPageIndex.set(1);
        this.loadReviews();
      },
      error: (err: unknown) => {
        this.reviewSaving.set(false);
        this.reviewError.set(translateError(err, this.transloco));
      },
    });
  }

  protected flagReview(review: ProductReview): void {
    this.reviewFlaggingId.set(review.id);
    this.reviewFlagMessage.set(null);
    this.productService.flagReview(review.id).subscribe({
      next: (result) => {
        this.reviewFlaggingId.set(null);
        this.reviewFlagMessage.set(
          this.transloco.translate(
            result.hidden ? 'product-detail.flagSuccessHidden' : 'product-detail.flagSuccess',
          ),
        );
        if (result.hidden) this.loadReviews();
      },
      error: (err: unknown) => {
        this.reviewFlaggingId.set(null);
        this.reviewFlagMessage.set(translateError(err, this.transloco));
      },
    });
  }
}
