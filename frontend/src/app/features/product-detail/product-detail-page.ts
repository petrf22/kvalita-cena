import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
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
import { ProductService } from '../../services/product-service';
import { QualityBadge } from '../../shared/quality-badge';
import { formatRelativeDate } from '../../shared/relative-date';
import { StorePicker } from '../../shared/store-picker';
import { PriceChart } from './price-chart';

const PRICE_KIND_LABELS: Record<PriceKind, string> = {
  REGULAR: 'Běžná cena',
  PROMO: 'Akce',
  CLUB_CARD: 'Klubová karta',
  CLEARANCE: 'Výprodej',
  MULTIBUY: 'Množstevní sleva',
};

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
  ],
  templateUrl: './product-detail-page.html',
  styleUrl: './product-detail-page.css',
})
export class ProductDetailPage {
  private readonly route = inject(ActivatedRoute);
  private readonly productService = inject(ProductService);
  protected readonly auth = inject(AuthService);

  protected readonly priceKindLabels = PRICE_KIND_LABELS;
  protected readonly chartRanges = CHART_RANGES;
  protected readonly formatRelativeDate = formatRelativeDate;

  protected readonly product = signal<Product | null>(null);
  protected readonly loading = signal(true);

  protected readonly historyPoints = signal<PricePoint[]>([]);
  protected readonly historyLoading = signal(false);
  protected readonly selectedDays = signal(90);
  protected readonly selectedPriceKind = signal<PriceKind>('REGULAR');

  protected readonly ratingError = signal<string | null>(null);

  protected readonly flagging = signal(false);
  protected readonly flagMessage = signal<string | null>(null);

  protected readonly selectedStoreId = signal<string | null>(null);
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
    this.productService.priceHistory(product.id, this.selectedPriceKind(), this.selectedDays()).subscribe({
      next: (history) => {
        this.historyPoints.set(history.points);
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
        this.ratingError.set('Hodnocení kvality vyžaduje přihlášení — přejdi do záložky Účet.');
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
          result.hidden
            ? 'Díky za nahlášení — položka je teď skrytá a čeká na přezkum.'
            : 'Díky za nahlášení, zaznamenali jsme ho.',
        );
      },
      error: () => {
        this.flagging.set(false);
        this.flagMessage.set('Nahlášení se nepovedlo, zkus to prosím znovu.');
      },
    });
  }

  /** null, když provozovna zatím nemá souřadnice (založená bez GPS, docs/datovy-model.md) — pak se nedá otevřít na mapě. */
  osmUrl(store: Store): string | null {
    if (store.lat == null || store.lon == null) return null;
    return `https://www.openstreetmap.org/?mlat=${store.lat}&mlon=${store.lon}#map=18/${store.lat}/${store.lon}`;
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
        error: () => {
          this.submitting.set(false);
          this.submitError.set('Zápis se nepovedl, zkus to prosím znovu.');
        },
      });
  }
}
