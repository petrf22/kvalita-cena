import { CurrencyPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzEmptyModule } from 'ng-zorro-antd/empty';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzPaginationModule } from 'ng-zorro-antd/pagination';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { ProductSearchItem, ProductSort, SearchFacets } from '../../models/catalog';
import { ProductService } from '../../services/product-service';
import { QualityBadge } from '../../shared/quality-badge';
import { formatRelativeDate } from '../../shared/relative-date';

const PAGE_SIZE = 20;

const SORT_OPTIONS: { value: ProductSort; label: string }[] = [
  { value: 'REPORT_COUNT', label: 'Podle počtu hlášení' },
  { value: 'PRICE_ASC', label: 'Podle ceny' },
  { value: 'QUALITY', label: 'Podle kvality' },
  { value: 'LAST_REPORTED', label: 'Podle posledního hlášení' },
  { value: 'NAME', label: 'Podle názvu' },
];

/**
 * Hledání s filtrem obchod/město a řazením (viz zadání) — mobilní protějšek:
 * mobile ui/search/SearchScreen.kt. Filtry i řazení jsou serverové, ne klientské — data jsou
 * stránkovaná, klientské řazení by řadilo jen viditelnou stránku.
 */
@Component({
  selector: 'app-search-page',
  imports: [
    FormsModule,
    CurrencyPipe,
    NzInputModule,
    NzButtonModule,
    NzTableModule,
    NzIconModule,
    NzEmptyModule,
    NzSelectModule,
    NzPaginationModule,
    QualityBadge,
  ],
  templateUrl: './search-page.html',
  styleUrl: './search-page.css',
})
export class SearchPage {
  private readonly productService = inject(ProductService);
  private readonly router = inject(Router);

  protected readonly sortOptions = SORT_OPTIONS;
  protected readonly formatRelativeDate = formatRelativeDate;

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
    this.productService.searchFacets().subscribe({
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
    this.router.navigate(['/produkt', item.product.id]);
  }
}
