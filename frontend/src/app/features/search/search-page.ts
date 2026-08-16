import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslocoDirective, TranslocoPipe, provideTranslocoScope } from '@jsverse/transloco';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzEmptyModule } from 'ng-zorro-antd/empty';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzPaginationModule } from 'ng-zorro-antd/pagination';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { ProductSearchItem, ProductSort, SearchFacets } from '../../models/catalog';
import { CountryService } from '../../services/country-service';
import { ProductService } from '../../services/product-service';
import { PRODUCT_SORT_KEYS } from '../../shared/enum-labels';
import { MoneyPipe } from '../../shared/money.pipe';
import { QualityBadge } from '../../shared/quality-badge';
import { RelativeDatePipe } from '../../shared/relative-date.pipe';
import { storeLabel } from '../../shared/store-label';

const PAGE_SIZE = 20;

// Pořadí zobrazení ve filtru — hodnoty samotné (a jejich popisky) drží PRODUCT_SORT_KEYS,
// jediný zdroj pravdy nad generovaným enumem (docs/lokalizace.md).
const SORT_ORDER = ['REPORT_COUNT', 'PRICE_ASC', 'QUALITY', 'LAST_REPORTED', 'NAME'] as const;

/**
 * Hledání s filtrem obchod/město a řazením (viz zadání) — mobilní protějšek:
 * mobile ui/search/SearchScreen.kt. Filtry i řazení jsou serverové, ne klientské — data jsou
 * stránkovaná, klientské řazení by řadilo jen viditelnou stránku.
 */
@Component({
  selector: 'app-search-page',
  imports: [
    FormsModule,
    NzInputModule,
    NzButtonModule,
    NzTableModule,
    NzIconModule,
    NzEmptyModule,
    NzSelectModule,
    NzPaginationModule,
    NzTagModule,
    QualityBadge,
    RelativeDatePipe,
    MoneyPipe,
    TranslocoDirective,
    TranslocoPipe,
  ],
  providers: [provideTranslocoScope('search')],
  templateUrl: './search-page.html',
  styleUrl: './search-page.css',
})
export class SearchPage {
  private readonly productService = inject(ProductService);
  private readonly router = inject(Router);
  protected readonly countryService = inject(CountryService);
  protected readonly storeLabel = storeLabel;

  protected readonly sortOrder = SORT_ORDER;
  protected readonly sortKeys = PRODUCT_SORT_KEYS;

  protected readonly query = signal('');
  protected readonly storeId = signal<string | null>(null);
  protected readonly city = signal<string | null>(null);
  protected readonly sort = signal<ProductSort>('REPORT_COUNT');
  protected readonly pageIndex = signal(1);

  protected readonly items = signal<ProductSearchItem[]>([]);
  protected readonly totalCount = signal(0);
  protected readonly loading = signal(false);
  protected readonly hasSearched = signal(false);
  protected readonly facets = signal<SearchFacets>({ stores: [], cities: [] });

  constructor() {
    // country jde vždy explicitně z CountryService (klient je autoritativní, docs/lokalizace.md)
    // — bez toho by appka spadla na app_user.country/Accept-Language na serveru, což by se
    // rozešlo s tím, co si uživatel zvolil v Nastavení (Čech žijící v Polsku).
    this.productService.searchFacets(this.countryService.country()).subscribe({
      // Filtry jsou volitelný doplněk hledání — chyba tady nesmí zablokovat samotné hledání.
      next: (facets) => this.facets.set(facets),
      error: () => {},
    });
  }

  search(): void {
    const q = this.query().trim();
    if (!q) {
      this.items.set([]);
      this.totalCount.set(0);
      this.hasSearched.set(false);
      return;
    }
    this.pageIndex.set(1);
    this.runSearch();
  }

  onFilterChange(): void {
    if (!this.query().trim()) return;
    this.pageIndex.set(1);
    this.runSearch();
  }

  onPageIndexChange(pageIndex: number): void {
    this.pageIndex.set(pageIndex);
    this.runSearch();
  }

  private runSearch(): void {
    const q = this.query().trim();
    if (!q) return;

    this.hasSearched.set(true);
    this.loading.set(true);
    this.productService
      .searchProducts({
        query: q,
        storeId: this.storeId(),
        city: this.city(),
        country: this.countryService.country(),
        sort: this.sort(),
        first: PAGE_SIZE,
        offset: (this.pageIndex() - 1) * PAGE_SIZE,
      })
      .subscribe({
        next: (result) => {
          this.items.set(result.items);
          this.totalCount.set(result.totalCount);
          this.loading.set(false);
        },
        error: () => {
          this.items.set([]);
          this.totalCount.set(0);
          this.loading.set(false);
        },
      });
  }

  openProduct(item: ProductSearchItem): void {
    this.router.navigate(['/product', item.product.id]);
  }
}
