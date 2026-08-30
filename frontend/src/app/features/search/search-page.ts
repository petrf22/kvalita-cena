import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslocoDirective, TranslocoPipe, provideTranslocoScope } from '@jsverse/transloco';
import type { NzTreeNode } from 'ng-zorro-antd/core/tree';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzEmptyModule } from 'ng-zorro-antd/empty';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzPaginationModule } from 'ng-zorro-antd/pagination';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { NzTreeSelectModule } from 'ng-zorro-antd/tree-select';
import { ProductSearchItem, ProductSort, SearchFacets } from '../../models/catalog';
import type { CategoriesQuery } from '../../models/generated/graphql';
import { CountryService } from '../../services/country-service';
import { INTL_TAGS, LanguageService } from '../../services/language-service';
import { ProductService } from '../../services/product-service';
import { buildCategoryTree, categoryBreadcrumb } from '../../shared/category-tree';
import { PRODUCT_SORT_KEYS } from '../../shared/enum-labels';
import { MoneyPipe } from '../../shared/money.pipe';
import { ProductThumb, usesExternalImageFallback } from '../../shared/product-thumb';
import { QualityBadge } from '../../shared/quality-badge';
import { RelativeDatePipe } from '../../shared/relative-date.pipe';
import { storeLabel } from '../../shared/store-label';

type CategoryOption = CategoriesQuery['categories'][number];

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
    NzTreeSelectModule,
    ProductThumb,
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
  private readonly language = inject(LanguageService);
  protected readonly countryService = inject(CountryService);
  protected readonly storeLabel = storeLabel;

  protected readonly sortOrder = SORT_ORDER;
  protected readonly sortKeys = PRODUCT_SORT_KEYS;

  protected readonly query = signal('');
  protected readonly storeId = signal<string | null>(null);
  protected readonly city = signal<string | null>(null);
  protected readonly categoryId = signal<string | null>(null);
  protected readonly sort = signal<ProductSort>('REPORT_COUNT');
  protected readonly pageIndex = signal(1);

  protected readonly items = signal<ProductSearchItem[]>([]);
  /** Aspoň jeden řádek ukazuje obrázek z Open Food Facts místo vlastní fotky — atribuce zdroje
   *  se do řádku tabulky nevejde, appka ji proto shrne jednou pod celý seznam (ODbL). */
  protected readonly showExternalImageNote = computed(() =>
    this.items().some((item) => usesExternalImageFallback(item.product)),
  );
  protected readonly totalCount = signal(0);
  protected readonly loading = signal(false);
  protected readonly hasSearched = signal(false);
  protected readonly facets = signal<SearchFacets>({ stores: [], cities: [] });
  protected readonly categories = signal<CategoryOption[]>([]);

  /** Strom pro `nz-tree-select` (shared/category-tree.ts) — stejný vzor jako product-form. */
  protected readonly categoryTree = computed(() =>
    buildCategoryTree(this.categories(), INTL_TAGS[this.language.lang()]),
  );
  protected readonly categoryDisplayWith = (node: NzTreeNode): string =>
    categoryBreadcrumb(node.key, this.categories());

  constructor() {
    // country jde vždy explicitně z CountryService (klient je autoritativní, docs/lokalizace.md)
    // — bez toho by appka spadla na app_user.country/Accept-Language na serveru, což by se
    // rozešlo s tím, co si uživatel zvolil v Nastavení (Čech žijící v Polsku).
    this.productService.searchFacets(this.countryService.country()).subscribe({
      // Filtry jsou volitelný doplněk hledání — chyba tady nesmí zablokovat samotné hledání.
      next: (facets) => this.facets.set(facets),
      error: () => {},
    });
    // Číselník kategorií jde přes Query.categories, ne přes SearchFacets — je to fixní
    // kurátorský strom, ne datově odvozený seznam jako obchody/města (a strom potřebuje
    // i rodiče bez zboží, aby šel poskládat).
    this.productService.categories().subscribe({
      next: (categories) => this.categories.set(categories),
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
        categoryId: this.categoryId(),
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
