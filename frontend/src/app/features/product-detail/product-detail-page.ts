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
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { PriceKind, PricePoint, Product } from '../../models/catalog';
import { AuthService } from '../../services/auth-service';
import { FormatService } from '../../services/format-service';
import { NavigationHistoryService } from '../../services/navigation-history-service';
import { ProductService } from '../../services/product-service';
import { NET_CONTENT_UOM_KEYS, PRICE_KIND_KEYS } from '../../shared/enum-labels';
import { MoneyPipe } from '../../shared/money.pipe';
import { PhotoGallery } from '../../shared/photo-gallery';
import { PriceEntryForm } from '../../shared/price-entry-form';
import { QualityBadge } from '../../shared/quality-badge';
import { RelativeDatePipe } from '../../shared/relative-date.pipe';
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
    NzSelectModule,
    NzAlertModule,
    QualityBadge,
    PriceEntryForm,
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

  protected readonly flagging = signal(false);
  protected readonly flagMessage = signal<string | null>(null);

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
}
