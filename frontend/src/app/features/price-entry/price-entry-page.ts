import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzDatePickerModule } from 'ng-zorro-antd/date-picker';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { PriceKind, Product, ProductSearchItem, QuantityBasis } from '../../models/catalog';
import { AuthService } from '../../services/auth-service';
import { ProductService } from '../../services/product-service';
import { StorePicker } from '../../shared/store-picker';
import { ProductForm } from '../product-form/product-form';

const PRICE_KIND_LABELS: Record<PriceKind, string> = {
  REGULAR: 'Běžná cena',
  PROMO: 'Akce',
  CLUB_CARD: 'Klubová karta',
  CLEARANCE: 'Výprodej',
  MULTIBUY: 'Množstevní sleva',
};

const QUANTITY_BASIS_LABELS: Record<QuantityBasis, string> = {
  PACKAGE: 'Za balení',
  PER_KG: 'Za kilogram',
  PER_L: 'Za litr',
  PER_PIECE: 'Za kus',
};

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
    NzInputNumberModule,
    NzSelectModule,
    NzRadioModule,
    NzButtonModule,
    NzAlertModule,
    NzSpinModule,
    NzDatePickerModule,
    StorePicker,
    ProductForm,
  ],
  templateUrl: './price-entry-page.html',
  styleUrl: './price-entry-page.css',
})
export class PriceEntryPage {
  private readonly productService = inject(ProductService);
  protected readonly auth = inject(AuthService);

  protected readonly priceKindLabels = PRICE_KIND_LABELS;
  protected readonly quantityBasisLabels = QUANTITY_BASIS_LABELS;

  protected readonly searchMode = signal<'name' | 'code'>('name');
  protected readonly nameQuery = signal('');
  protected readonly codeQuery = signal('');
  protected readonly searching = signal(false);
  protected readonly searchError = signal<string | null>(null);
  protected readonly results = signal<ProductSearchItem[]>([]);
  protected readonly codeNotFound = signal(false);

  protected readonly showProductForm = signal(false);
  protected readonly selectedProduct = signal<Product | null>(null);
  protected readonly loadingProduct = signal(false);

  protected readonly selectedStoreId = signal<string | null>(null);
  protected readonly priceAmount = signal<number | null>(null);
  protected readonly priceKind = signal<PriceKind>('REGULAR');
  protected readonly quantityBasis = signal<QuantityBasis>('PACKAGE');
  protected readonly observedAt = signal<Date | null>(null);

  protected readonly submitting = signal(false);
  protected readonly submitSuccess = signal(false);
  protected readonly submitError = signal<string | null>(null);

  /** observed_at nesmí být v budoucnosti — uživatel zapisuje cenu, kterou už viděl. */
  protected readonly isFutureDate = (date: Date): boolean => date.getTime() > Date.now();

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
        this.searchError.set('Hledání se nepovedlo, zkus to prosím znovu.');
      },
    });
  }

  searchByCode(): void {
    const code = this.codeQuery().trim();
    if (!code) return;
    this.searching.set(true);
    this.searchError.set(null);
    this.codeNotFound.set(false);
    this.results.set([]);
    this.productService.getByCode(code).subscribe({
      next: (product) => {
        this.searching.set(false);
        if (product) this.selectedProduct.set(product);
        else this.codeNotFound.set(true);
      },
      error: () => {
        this.searching.set(false);
        this.searchError.set('Hledání se nepovedlo, zkus to prosím znovu.');
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
    this.selectedProduct.set(product);
  }

  changeProduct(): void {
    this.selectedProduct.set(null);
    this.results.set([]);
    this.codeNotFound.set(false);
    this.submitSuccess.set(false);
    this.priceAmount.set(null);
  }

  submit(): void {
    const product = this.selectedProduct();
    const storeId = this.selectedStoreId();
    const priceAmount = this.priceAmount();
    if (!product || !storeId || priceAmount == null) return;

    this.submitting.set(true);
    this.submitError.set(null);
    this.submitSuccess.set(false);
    const observedAt = this.observedAt();
    this.productService
      .submitObservation({
        productId: product.id,
        storeId,
        priceAmount,
        priceKind: this.priceKind(),
        quantityBasis: product.isVariableWeight ? this.quantityBasis() : 'PACKAGE',
        observedAt: observedAt ? observedAt.toISOString() : undefined,
      })
      .subscribe({
        next: () => {
          this.submitting.set(false);
          this.submitSuccess.set(true);
          this.priceAmount.set(null);
          // Obnoví agregované ceny na vybraném produktu (stejný princip jako product-detail-page).
          this.productService.getById(product.id).subscribe({ next: (p) => p && this.selectedProduct.set(p) });
        },
        error: () => {
          this.submitting.set(false);
          this.submitError.set('Zápis se nepovedl, zkus to prosím znovu.');
        },
      });
  }
}
