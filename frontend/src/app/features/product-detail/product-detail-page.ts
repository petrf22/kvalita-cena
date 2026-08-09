import { Component, inject, signal } from '@angular/core';
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
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { PriceKind, PricePoint, Product, Store } from '../../models/catalog';
import { AuthService } from '../../services/auth-service';
import { FormatService } from '../../services/format-service';
import { ProductService } from '../../services/product-service';
import { currencyForCountry } from '../../shared/country-currency';
import { translateError } from '../../shared/error-message';
import {
  NET_CONTENT_UOM_KEYS,
  PRICE_KIND_KEYS,
  SELECTABLE_PRICE_KINDS,
} from '../../shared/enum-labels';
import { MoneyPipe } from '../../shared/money.pipe';
import { PhotoGallery } from '../../shared/photo-gallery';
import { QualityBadge } from '../../shared/quality-badge';
import { RelativeDatePipe } from '../../shared/relative-date.pipe';
import { StorePicker } from '../../shared/store-picker';
import { PriceChart } from './price-chart';

const CHART_RANGES = [7, 30, 90, 365];

@Component({
  selector: 'app-product-detail-page',
  imports: [
    FormsModule,
    RouterLink,
    NzCardModule,
    NzTableModule,
    NzTagModule,
    NzButtonModule,
    NzIconModule,
    NzFormModule,
    NzSelectModule,
    NzInputModule,
    NzInputNumberModule,
    NzAlertModule,
    QualityBadge,
    StorePicker,
    PriceChart,
    PhotoGallery,
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
  private readonly productService = inject(ProductService);
  private readonly transloco = inject(TranslocoService);
  protected readonly auth = inject(AuthService);
  protected readonly format = inject(FormatService);

  protected readonly priceKindKeys = PRICE_KIND_KEYS;
  protected readonly netContentUomKeys = NET_CONTENT_UOM_KEYS;
  protected readonly selectablePriceKinds = SELECTABLE_PRICE_KINDS;
  protected readonly chartRanges = CHART_RANGES;

  protected readonly product = signal<Product | null>(null);
  protected readonly loading = signal(true);

  protected readonly historyPoints = signal<PricePoint[]>([]);
  protected readonly historyLoading = signal(false);
  protected readonly historyCurrency = signal<string | null>(null);
  protected readonly selectedDays = signal(90);
  protected readonly selectedPriceKind = signal<PriceKind>('REGULAR');

  protected readonly ratingError = signal<string | null>(null);

  protected readonly flagging = signal(false);
  protected readonly flagMessage = signal<string | null>(null);

  protected readonly selectedStoreId = signal<string | null>(null);
  protected readonly selectedStoreCurrency = signal<string>('CZK');
  protected readonly priceAmount = signal<number | null>(null);
  protected readonly priceKind = signal<PriceKind>('REGULAR');
  protected readonly submitting = signal(false);
  protected readonly submitSuccess = signal(false);
  protected readonly submitError = signal<string | null>(null);

  constructor() {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) this.loadProduct(id);
  }

  private loadProduct(id: string): void {
    this.loading.set(true);
    this.productService.getById(id).subscribe({
      next: (product) => {
        this.product.set(product);
        this.loading.set(false);
        if (product) this.loadHistory();
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

  /** Jen popisek pole "Cena (Kč/€/zł)" — o skutečně uložené měně rozhoduje server (docs/lokalizace.md). */
  onStoreChange(store: Store | null): void {
    this.selectedStoreCurrency.set(currencyForCountry(store?.country));
  }

  rate(grade: number): void {
    const product = this.product();
    if (!product) return;

    this.ratingError.set(null);
    this.productService.rateProduct(product.id, grade).subscribe({
      next: (quality) => {
        this.product.set({ ...product, quality, myQualityRating: grade });
      },
      error: () => {
        this.ratingError.set(this.transloco.translate('product-detail.qualityLoginHint'));
      },
    });
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

  submitPrice(): void {
    const product = this.product();
    const storeId = this.selectedStoreId();
    const priceAmount = this.priceAmount();
    if (!product || !storeId || priceAmount == null) return;

    this.submitting.set(true);
    this.submitError.set(null);
    this.submitSuccess.set(false);
    this.productService
      .submitObservation({
        productId: product.id,
        storeId,
        priceAmount,
        priceKind: this.priceKind(),
      })
      .subscribe({
        next: () => {
          this.submitting.set(false);
          this.submitSuccess.set(true);
          this.priceAmount.set(null);
          this.loadProduct(product.id); // obnoví agregované ceny v tabulce i graf
        },
        error: (err: unknown) => {
          this.submitting.set(false);
          this.submitError.set(translateError(err, this.transloco));
        },
      });
  }
}
