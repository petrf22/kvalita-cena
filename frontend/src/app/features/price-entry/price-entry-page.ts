import { Component, computed, inject, signal } from '@angular/core';
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
import { Product, ProductSummary, Store } from '../../models/catalog';
import { AuthService } from '../../services/auth-service';
import { ProductService } from '../../services/product-service';
import { PRICE_KIND_KEYS } from '../../shared/enum-labels';
import { MoneyPipe } from '../../shared/money.pipe';
import { PriceEntryForm } from '../../shared/price-entry-form';
import {
  ProductPreview,
  ProductThumb,
  usesExternalImageFallback,
} from '../../shared/product-thumb';
import { StorePicker } from '../../shared/store-picker';
import { ExistingProductMatch, ProductForm } from '../product-form/product-form';

const SUGGESTIONS_DEBOUNCE_MS = 300;

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
    ProductPreview,
    ProductThumb,
    StorePicker,
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
  protected readonly selectedStore = signal<Store | null>(null);
  protected readonly nameQuery = signal('');
  protected readonly codeQuery = signal('');
  protected readonly searching = signal(false);
  protected readonly searchError = signal<string | null>(null);
  protected readonly results = signal<ProductSummary[]>([]);
  /** Aspoň jeden výsledek ukazuje obrázek z Open Food Facts místo vlastní fotky — atribuce
   *  zdroje se do řádku nevejde, appka ji shrne jednou pod celý seznam (ODbL). */
  protected readonly showExternalImageNote = computed(() =>
    this.results().some((item) => usesExternalImageFallback(item)),
  );
  protected readonly codeNotFound = signal(false);
  /** OFF katalog je teď nedostupný (výpadek/rate limit) — jiná hláška než "nemáme ho" pod codeNotFound. */
  protected readonly offUnavailable = signal(false);

  /** Seznam ukazuje celou nabídku obchodu, ne výsledky hledání — jiný nadpis nad stejným seznamem. */
  protected readonly browsingStoreOffer = signal(false);
  private suggestionsTimer?: ReturnType<typeof setTimeout>;

  protected readonly showProductForm = signal(false);
  protected readonly selectedProduct = signal<Product | null>(null);
  protected readonly productAlias = signal<string | null>(null);
  /** Vybrané zboží ukazuje místo vlastní fotky obrázek z Open Food Facts — atribuce zdroje
   *  musí být vidět u něj (ODbL), na rozdíl od seznamu ji sem lze napsat rovnou pod náhled. */
  protected readonly selectedUsesExternalImage = computed(() => {
    const product = this.selectedProduct();
    return product != null && usesExternalImageFallback(product);
  });
  protected readonly loadingProduct = signal(false);
  /** Právě proběhl zápis ceny — nabídne "zapsat další" místo návratu na začátek. */
  protected readonly justSubmitted = signal(false);

  /**
   * Nabídka pro vybraný obchod. S dotazem podobné položky, BEZ dotazu celá lokální nabídka té
   * provozovny — u bezkódového zboží nevznikají duplicity ani tak překlepy jako tím, že člověk
   * nemá co odklepnout, tak ať vidí, co v obchodě je, dřív než začne vymýšlet vlastní název
   * (docs/reputace.md, "Zboží bez čárového kódu").
   */
  private loadSuggestions(query: string): void {
    const store = this.selectedStore();
    if (!store) return;
    this.searching.set(true);
    this.searchError.set(null);
    this.codeNotFound.set(false);
    this.browsingStoreOffer.set(query.length === 0);
    // productSuggestions (ne searchProducts) — storeId u searchProducts filtruje agregáty
    // (agg.price_current), takže by tu ukázal jen zboží, které v obchodě UŽ cenu má. Tady
    // hledáme napříč katalogem v rozsahu obchodu/řetězce a chceme i potvrzené aliasy.
    this.productService.suggestions(query, store.id, 10).subscribe({
      next: (result) => {
        this.results.set(result);
        this.searching.set(false);
      },
      error: () => {
        this.results.set([]);
        this.searching.set(false);
        this.searchError.set(this.transloco.translate('price-entry.searchFailed'));
      },
    });
  }

  /** Našeptává průběžně, ne až na tlačítko — kdo dopíše celý vymyšlený název a teprve pak uvidí
   *  existující položku, si jí většinou už nevšimne. Stejný debounce jako `product-form`. */
  onNameChange(value: string): void {
    this.nameQuery.set(value);
    clearTimeout(this.suggestionsTimer);
    this.suggestionsTimer = setTimeout(
      () => this.loadSuggestions(value.trim()),
      SUGGESTIONS_DEBOUNCE_MS,
    );
  }

  searchByName(): void {
    clearTimeout(this.suggestionsTimer);
    this.loadSuggestions(this.nameQuery().trim());
  }

  onSearchModeChange(mode: 'name' | 'code'): void {
    this.searchMode.set(mode);
    clearTimeout(this.suggestionsTimer);
    this.results.set([]);
    this.codeNotFound.set(false);
    this.offUnavailable.set(false);
    if (mode === 'name') this.loadSuggestions(this.nameQuery().trim());
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

  selectFromResults(item: ProductSummary): void {
    this.loadingProduct.set(true);
    this.productService.getById(item.id).subscribe({
      next: (product) => {
        this.loadingProduct.set(false);
        if (product) {
          this.selectedProduct.set(product);
          this.productAlias.set(this.nameQuery().trim() || null);
        }
      },
      error: () => this.loadingProduct.set(false),
    });
  }

  openProductForm(): void {
    this.showProductForm.set(true);
  }

  onProductCreated(product: Product): void {
    this.showProductForm.set(false);
    this.justSubmitted.set(false);
    this.codeNotFound.set(false);
    this.offUnavailable.set(false);
    this.selectedProduct.set(product);
    this.productAlias.set(null);
  }

  onExistingProductMatched(match: ExistingProductMatch): void {
    this.showProductForm.set(false);
    this.selectedProduct.set(match.product);
    this.productAlias.set(match.alias);
  }

  onStoreChange(store: Store | null): void {
    const previous = this.selectedStore();
    this.selectedStore.set(store);
    if (previous?.id === store?.id) return;
    this.selectedProduct.set(null);
    this.productAlias.set(null);
    this.results.set([]);
    this.showProductForm.set(false);
    clearTimeout(this.suggestionsTimer);
    if (store && this.searchMode() === 'name') this.loadSuggestions(this.nameQuery().trim());
  }

  changeProduct(): void {
    this.selectedProduct.set(null);
    this.justSubmitted.set(false);
    this.results.set([]);
    this.codeNotFound.set(false);
    this.offUnavailable.set(false);
    this.productAlias.set(null);
    if (this.searchMode() === 'name') this.loadSuggestions(this.nameQuery().trim());
  }

  /** Obnoví agregované ceny na vybraném produktu po úspěšném zápisu (`app-price-entry-form`). */
  onPricesSubmitted(productId: string): void {
    this.justSubmitted.set(true);
    this.productService.getById(productId).subscribe({
      next: (product) => product && this.selectedProduct.set(product),
    });
  }

  /**
   * Typický scénář je nákup, ne jedna položka — po zápisu proto nabídne rovnou další zboží
   * z TÉHOŽ obchodu místo prázdného formuláře. Víc záznamů na položku je zároveň nejlevnější
   * cesta k vyššímu n_eff v agregátu (docs/reputace.md).
   */
  enterAnother(): void {
    this.justSubmitted.set(false);
    this.nameQuery.set('');
    this.searchMode.set('name');
    this.changeProduct();
  }
}
