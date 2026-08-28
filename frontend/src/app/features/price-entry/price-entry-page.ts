import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  TranslocoDirective,
  TranslocoPipe,
  TranslocoService,
  provideTranslocoScope,
} from '@jsverse/transloco';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { Product, ProductSearchItem } from '../../models/catalog';
import { AuthService } from '../../services/auth-service';
import { ProductService } from '../../services/product-service';
import { PRICE_KIND_KEYS } from '../../shared/enum-labels';
import { MoneyPipe } from '../../shared/money.pipe';
import { PriceEntryForm } from '../../shared/price-entry-form';
import { ProductForm } from '../product-form/product-form';

/**
 * Samostatná stránka "Zadat cenu" — na rozdíl od formuláře v detailu produktu (který
 * předpokládá, že produkt už znáš) tahle stránka nejdřív najde/založí zboží, teprve pak
 * obchod a cenu. Mobilní protějšek: mobile ui/price/PriceEntryScreen.kt + ProductFormScreen.
 * Web navíc řeší zpětné datum (`observedAt`) — web je typicky zpětné zapisování doma, kde
 * observed_at ≠ created_at skutečně přichází ke slovu (docs/datovy-model.md).
 */
@Component({
  selector: 'app-price-entry-page',
  imports: [
    FormsModule,
    NzFormModule,
    NzInputModule,
    NzRadioModule,
    NzButtonModule,
    NzAlertModule,
    NzSpinModule,
    PriceEntryForm,
    ProductForm,
    TranslocoDirective,
    TranslocoPipe,
    MoneyPipe,
  ],
  providers: [provideTranslocoScope('price-entry')],
  templateUrl: './price-entry-page.html',
  styleUrl: './price-entry-page.css',
})
export class PriceEntryPage {
  private readonly productService = inject(ProductService);
  private readonly transloco = inject(TranslocoService);
  protected readonly auth = inject(AuthService);

  protected readonly priceKindKeys = PRICE_KIND_KEYS;

  protected readonly searchMode = signal<'name' | 'code'>('name');
  protected readonly nameQuery = signal('');
  protected readonly codeQuery = signal('');
  protected readonly searching = signal(false);
  protected readonly searchError = signal<string | null>(null);
  protected readonly results = signal<ProductSearchItem[]>([]);
  protected readonly codeNotFound = signal(false);
  /** OFF katalog je teď nedostupný (výpadek/rate limit) — jiná hláška než "nemáme ho" pod codeNotFound. */
  protected readonly offUnavailable = signal(false);

  protected readonly showProductForm = signal(false);
  protected readonly selectedProduct = signal<Product | null>(null);
  protected readonly loadingProduct = signal(false);

  searchByName(): void {
    const query = this.nameQuery().trim();
    if (!query) return;
    this.searching.set(true);
    this.searchError.set(null);
    this.codeNotFound.set(false);
    this.productService.searchProducts({ query, first: 10 }).subscribe({
      next: (result) => {
        this.results.set(result.items);
        this.searching.set(false);
      },
      error: () => {
        this.results.set([]);
        this.searching.set(false);
        this.searchError.set(this.transloco.translate('price-entry.searchFailed'));
      },
    });
  }

  searchByCode(): void {
    const code = this.codeQuery().trim();
    if (!code) return;
    this.searching.set(true);
    this.searchError.set(null);
    this.codeNotFound.set(false);
    this.offUnavailable.set(false);
    this.results.set([]);
    this.productService.lookupByCode(code).subscribe({
      next: (result) => {
        this.searching.set(false);
        if (result.status === 'EXISTING' && result.product) {
          this.selectedProduct.set(result.product);
          return;
        }
        // NOT_FOUND i OFF_CANDIDATE vedou na stejné tlačítko "Založit nové" — product-form si
        // OFF kandidáta dotáhne (a předvyplní) sám ze stejné cache, druhé volání je zdarma.
        this.codeNotFound.set(true);
        if (result.status === 'OFF_UNAVAILABLE') this.offUnavailable.set(true);
      },
      error: () => {
        this.searching.set(false);
        this.searchError.set(this.transloco.translate('price-entry.searchFailed'));
      },
    });
  }

  selectFromResults(item: ProductSearchItem): void {
    this.loadingProduct.set(true);
    this.productService.getById(item.product.id).subscribe({
      next: (product) => {
        this.loadingProduct.set(false);
        if (product) this.selectedProduct.set(product);
      },
      error: () => this.loadingProduct.set(false),
    });
  }

  openProductForm(): void {
    this.showProductForm.set(true);
  }

  onProductCreated(product: Product): void {
    this.showProductForm.set(false);
    this.codeNotFound.set(false);
    this.offUnavailable.set(false);
    this.selectedProduct.set(product);
  }

  changeProduct(): void {
    this.selectedProduct.set(null);
    this.results.set([]);
    this.codeNotFound.set(false);
    this.offUnavailable.set(false);
  }

  /** Obnoví agregované ceny na vybraném produktu po úspěšném zápisu (`app-price-entry-form`). */
  onPricesSubmitted(productId: string): void {
    this.productService.getById(productId).subscribe({
      next: (product) => product && this.selectedProduct.set(product),
    });
  }
}
