import { Component, EventEmitter, Input, Output, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  TranslocoDirective,
  TranslocoPipe,
  TranslocoService,
  provideTranslocoScope,
} from '@jsverse/transloco';
import { Observable, catchError, concatMap, finalize, from, map, of, toArray } from 'rxjs';
import type { NzTreeNode } from 'ng-zorro-antd/core/tree';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTreeSelectModule } from 'ng-zorro-antd/tree-select';
import { ExternalProductCandidate, Product, ProductSummary, UnitBase } from '../../models/catalog';
import type { CategoriesQuery } from '../../models/generated/graphql';
import { FormatService } from '../../services/format-service';
import { INTL_TAGS, LanguageService } from '../../services/language-service';
import { MediaService } from '../../services/media-service';
import { ProductService } from '../../services/product-service';
import { buildCategoryTree, categoryBreadcrumb } from '../../shared/category-tree';
import { translateError } from '../../shared/error-message';
import { UNIT_BASE_KEYS } from '../../shared/enum-labels';
import { PhotoSlot } from '../../shared/photo-slot';
import {
  OffCandidateDefaults,
  changedFromOff,
  codeMatchesOffCandidate,
  impliedNetContentUom,
  isProductFormValid,
  netContentForOffSubmit,
  offCandidateDefaults,
  pendingPhotoUploads,
} from './product-form-validation';

type CategoryOption = CategoriesQuery['categories'][number];

const SUGGESTIONS_DEBOUNCE_MS = 300;

/** Pořadí ve formuláři — popisky drží UNIT_BASE_KEYS, jediný zdroj pravdy (docs/lokalizace.md). */
const UNIT_BASE_ORDER: readonly UnitBase[] = ['COUNT', 'MASS', 'VOLUME'];

/**
 * Založení zboží — s naskenovaným EANem i bez něj. Bezkódové zboží (žádný kód na obalu, jen
 * "pečivo za 45 Kč" na účtence, nebo podniková prodejna zemědělského družstva bez EANu) vznikne
 * jako druhová položka (docs/reputace.md, "Zboží bez čárového kódu") — server ji založí jako
 * DRAFT/isGeneric a confidence zastropuje na MEDIUM, appka tu nic z toho neřeší. Mobilní
 * protějšek: mobile ui/product/ProductFormScreen.kt.
 */
@Component({
  selector: 'app-product-form',
  imports: [
    FormsModule,
    NzFormModule,
    NzInputModule,
    NzInputNumberModule,
    NzTreeSelectModule,
    NzRadioModule,
    NzSwitchModule,
    NzButtonModule,
    NzAlertModule,
    TranslocoDirective,
    TranslocoPipe,
    PhotoSlot,
  ],
  providers: [provideTranslocoScope('product-form')],
  templateUrl: './product-form.html',
  styleUrl: './product-form.css',
})
export class ProductForm {
  private readonly productService = inject(ProductService);
  private readonly mediaService = inject(MediaService);
  protected readonly format = inject(FormatService);
  private readonly transloco = inject(TranslocoService);
  private readonly language = inject(LanguageService);

  /**
   * Naskenovaný/zadaný kód, který se v katalogu nenašel — předvyplní pole kódu a zkusí, jestli
   * ho nezná Open Food Facts (`productLookupByCode`, cache v `ProductService` — druhé volání po
   * `price-entry-page` je zdarma). Výpadek/nedostupnost OFF je tichý no-op, formulář zůstane
   * prázdný a ručně vyplnitelný.
   */
  @Input() set barcode(value: string | null | undefined) {
    if (!value) return;
    this.code.set(value);
    this.productService.lookupByCode(value).subscribe({
      next: (result) => {
        if (result.status === 'OFF_CANDIDATE' && result.candidate) {
          this.applyOffCandidate(result.candidate);
        }
      },
      error: () => {},
    });
  }

  @Output() readonly created = new EventEmitter<Product>();
  @Output() readonly cancelled = new EventEmitter<void>();

  protected readonly unitBaseOrder = UNIT_BASE_ORDER;
  protected readonly unitBaseKeys = UNIT_BASE_KEYS;

  protected readonly name = signal('');
  protected readonly suggestions = signal<ProductSummary[]>([]);
  protected readonly suggestionsLoading = signal(false);
  private suggestionsTimer?: ReturnType<typeof setTimeout>;

  protected readonly categories = signal<CategoryOption[]>([]);
  protected readonly selectedCategoryId = signal<string | null>(null);

  /** Strom pro `nz-tree-select` (shared/category-tree.ts) — přeskládaný podle sortOrder, ne
   *  podle abecedního pořadí, ve kterém kategorie vrací `Query.categories`. */
  protected readonly categoryTree = computed(() =>
    buildCategoryTree(this.categories(), INTL_TAGS[this.language.lang()]),
  );

  /** Uzavřené pole výběru ukáže celou větev ("Potraviny › Mléčné výrobky › Máslo"), ne jen
   *  list — samotné jméno listu by bez kontextu nebylo poznat, pod čím v číselníku leží. */
  protected readonly categoryDisplayWith = (node: NzTreeNode): string =>
    categoryBreadcrumb(node.key, this.categories());

  protected readonly brandName = signal('');
  protected readonly unitBase = signal<UnitBase>('COUNT');
  protected readonly netContentValue = signal<number | null>(null);
  protected readonly piecesInPack = signal<number | null>(null);
  protected readonly isVariableWeight = signal(false);
  protected readonly code = signal('');

  protected readonly itemPhotoFile = signal<File | null>(null);
  protected readonly labelPhotoFile = signal<File | null>(null);

  protected readonly saving = signal(false);
  protected readonly uploadingPhotos = signal(false);
  protected readonly photoUploadWarning = signal(false);
  protected readonly saveError = signal<string | null>(null);

  /** Nabídnutý OFF kandidát pro banner nad formulářem — null, dokud appka nic nenašla/nehledala. */
  protected readonly offCandidate = signal<ExternalProductCandidate | null>(null);
  /** Snímek předvyplněných hodnot (gramáž převedená na kg/l) — jen appka sama, ne pro šablonu. */
  private offDefaults: OffCandidateDefaults | null = null;

  constructor() {
    this.productService.categories().subscribe({
      next: (categories) => this.categories.set(categories),
      // Formulář jde vyplnit i bez číselníku — jen se pak nedá uložit (kategorie je povinná).
      error: () => {},
    });
  }

  onNameChange(value: string): void {
    this.name.set(value);
    clearTimeout(this.suggestionsTimer);
    if (!value.trim()) {
      this.suggestions.set([]);
      return;
    }
    this.suggestionsTimer = setTimeout(() => {
      this.suggestionsLoading.set(true);
      this.productService.suggestions(value.trim()).subscribe({
        next: (result) => {
          this.suggestions.set(result);
          this.suggestionsLoading.set(false);
        },
        error: () => this.suggestionsLoading.set(false),
      });
    }, SUGGESTIONS_DEBOUNCE_MS);
  }

  /** Předvyplní formulář z OFF kandidáta — gramáž převede na kg/l (`offCandidateDefaults`,
   *  past OFF kandidáta) a snímek pro submit() si uloží stranou do `offDefaults`. Nepřepisuje
   *  pole, která kandidát nemá (necháme prázdné pro ruční vyplnění). */
  private applyOffCandidate(candidate: ExternalProductCandidate): void {
    this.offCandidate.set(candidate);
    const defaults = offCandidateDefaults(candidate);
    this.offDefaults = defaults;
    if (defaults.name) this.name.set(defaults.name);
    if (defaults.brandName) this.brandName.set(defaults.brandName);
    if (defaults.categoryId) this.selectedCategoryId.set(defaults.categoryId);
    if (defaults.unitBase) this.unitBase.set(defaults.unitBase);
    if (defaults.netContentValue != null) this.netContentValue.set(defaults.netContentValue);
  }

  /** Uživatel si vybral existující nabídnutou položku místo založení nové (docs/reputace.md). */
  useExisting(summary: ProductSummary): void {
    this.saving.set(true);
    this.saveError.set(null);
    this.productService.getById(summary.id).subscribe({
      next: (product) => {
        this.saving.set(false);
        if (product) this.created.emit(product);
      },
      error: (err) => {
        this.saving.set(false);
        this.saveError.set(translateError(err, this.transloco));
      },
    });
  }

  isValid(): boolean {
    return isProductFormValid(this.name(), this.selectedCategoryId(), this.unitBase());
  }

  /**
   * Ilustrační příklad v nápovědě u kódu — formulář v tuhle chvíli neví, do jakého obchodu/země
   * zboží míří (na rozdíl od store-form, kde už `country` appka zná), takže CZK tu zůstává jen
   * jako záměrně zvolený, byť ne úplně přesný, vzor (docs/lokalizace.md).
   */
  protected codeHintExample(): string {
    return this.format.money(45, 'CZK');
  }

  submit(): void {
    const categoryId = this.selectedCategoryId();
    if (!this.isValid() || !categoryId) return;

    this.saving.set(true);
    this.saveError.set(null);

    const candidate = this.offCandidate();
    const defaults = this.offDefaults;
    // Kód se od nabídky kandidáta pořád musí shodovat — jinak uživatel kód smazal/přepsal
    // (bezkódová položka, jiné zboží) a appka musí uložit přes createProduct, ne
    // createProductFromOff (CLAUDE.md, past OFF kandidáta — OFF hodnoty se nesmí zapsat do
    // core.product jako vlastní).
    const request$ =
      candidate != null && defaults != null && codeMatchesOffCandidate(this.code(), candidate.code)
        ? this.createFromOff(candidate, defaults, categoryId)
        : this.productService.createProduct({
            name: this.name().trim(),
            brandName: this.brandName().trim() || null,
            categoryId,
            unitBase: this.unitBase(),
            netContentValue: this.isVariableWeight() ? null : this.netContentValue(),
            netContentUom: impliedNetContentUom(this.unitBase()),
            piecesInPack: this.piecesInPack(),
            isVariableWeight: this.isVariableWeight(),
            code: this.code().trim() || null,
          });

    request$.subscribe({
      next: (product) => {
        // Zboží už existuje — fotky se nahrávají VÝHRADNĚ na existující záznam
        // (docs/datovy-model.md), teprve teď má appka kam je poslat.
        this.uploadPendingPhotos(product.id).subscribe(() => {
          this.saving.set(false);
          this.created.emit(product);
        });
      },
      error: (err) => {
        this.saving.set(false);
        this.saveError.set(translateError(err, this.transloco));
      },
    });
  }

  /**
   * Nahraje vybrané fotky (fotka zboží první, pak etiketa) na právě založený produkt —
   * sekvenčně, ne najednou, ať appka nezahltí server dvěma souběžnými requesty za jeden submit.
   * Selhání jedné fotky nezastaví druhou ani neshodí založení zboží (`photoUploadWarning`);
   * produkt v tu chvíli už existuje, fotku jde doplnit později z jeho detailu.
   */
  private uploadPendingPhotos(productId: string): Observable<null> {
    const uploads = pendingPhotoUploads(this.itemPhotoFile(), this.labelPhotoFile());
    if (uploads.length === 0) return of(null);

    this.uploadingPhotos.set(true);
    return from(uploads).pipe(
      concatMap((upload) =>
        this.mediaService.upload('PRODUCT', productId, upload.file, null, upload.kind).pipe(
          map(() => true),
          catchError(() => {
            this.photoUploadWarning.set(true);
            return of(false);
          }),
        ),
      ),
      toArray(),
      map(() => null),
      finalize(() => this.uploadingPhotos.set(false)),
    );
  }

  /**
   * Založení nad potvrzeným OFF kandidátem — pole, která uživatel nezměnil oproti
   * `offCandidateDefaults`, se posílají jako `null`, ať je dál dodává OFF a nevznikne zbytečný
   * `core.product_user_edit` patch (CLAUDE.md, past OFF kandidáta; gramáž/objem se posílá vždy
   * spolu s jednotkou, viz `netContentForOffSubmit`).
   */
  private createFromOff(
    candidate: ExternalProductCandidate,
    defaults: OffCandidateDefaults,
    categoryId: string,
  ) {
    const text = changedFromOff(
      { name: this.name(), brandName: this.brandName(), categoryId },
      defaults,
    );
    const netContent = netContentForOffSubmit(
      this.isVariableWeight() ? null : this.netContentValue(),
      this.unitBase(),
      defaults.netContentValue,
    );
    return this.productService.createProductFromOff({
      code: candidate.code,
      name: text.name,
      brandName: text.brandName,
      categoryId: text.categoryId,
      unitBase: this.unitBase(),
      netContentValue: netContent.netContentValue,
      netContentUom: netContent.netContentUom,
      piecesInPack: this.piecesInPack(),
      isVariableWeight: this.isVariableWeight(),
    });
  }
}
