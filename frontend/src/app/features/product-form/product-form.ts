import { Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { Category, Product, ProductSummary, UnitBase } from '../../models/catalog';
import { ProductService } from '../../services/product-service';
import { impliedNetContentUom, isProductFormValid } from './product-form-validation';

const SUGGESTIONS_DEBOUNCE_MS = 300;

const UNIT_BASE_OPTIONS: { value: UnitBase; label: string }[] = [
  { value: 'COUNT', label: 'Kus' },
  { value: 'MASS', label: 'Hmotnost' },
  { value: 'VOLUME', label: 'Objem' },
];

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
    NzSelectModule,
    NzRadioModule,
    NzSwitchModule,
    NzButtonModule,
    NzAlertModule,
  ],
  templateUrl: './product-form.html',
  styleUrl: './product-form.css',
})
export class ProductForm {
  private readonly productService = inject(ProductService);

  /** Naskenovaný/zadaný kód, který se v katalogu nenašel — předvyplní pole kódu. */
  @Input() set barcode(value: string | null | undefined) {
    if (value) this.code.set(value);
  }

  @Output() readonly created = new EventEmitter<Product>();
  @Output() readonly cancelled = new EventEmitter<void>();

  protected readonly unitBaseOptions = UNIT_BASE_OPTIONS;

  protected readonly name = signal('');
  protected readonly suggestions = signal<ProductSummary[]>([]);
  protected readonly suggestionsLoading = signal(false);
  private suggestionsTimer?: ReturnType<typeof setTimeout>;

  protected readonly categories = signal<Category[]>([]);
  protected readonly selectedCategoryId = signal<string | null>(null);

  protected readonly brandName = signal('');
  protected readonly unitBase = signal<UnitBase>('COUNT');
  protected readonly netContentValue = signal<number | null>(null);
  protected readonly piecesInPack = signal<number | null>(null);
  protected readonly isVariableWeight = signal(false);
  protected readonly code = signal('');

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

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

  /** Uživatel si vybral existující nabídnutou položku místo založení nové (docs/reputace.md). */
  useExisting(summary: ProductSummary): void {
    this.saving.set(true);
    this.saveError.set(null);
    this.productService.getById(summary.id).subscribe({
      next: (product) => {
        this.saving.set(false);
        if (product) this.created.emit(product);
      },
      error: () => {
        this.saving.set(false);
        this.saveError.set('Nepodařilo se načíst vybrané zboží, zkus to prosím znovu.');
      },
    });
  }

  isValid(): boolean {
    return isProductFormValid(this.name(), this.selectedCategoryId(), this.unitBase());
  }

  submit(): void {
    const categoryId = this.selectedCategoryId();
    if (!this.isValid() || !categoryId) return;

    this.saving.set(true);
    this.saveError.set(null);
    this.productService
      .createProduct({
        name: this.name().trim(),
        brandName: this.brandName().trim() || null,
        categoryId,
        unitBase: this.unitBase(),
        netContentValue: this.isVariableWeight() ? null : this.netContentValue(),
        netContentUom: impliedNetContentUom(this.unitBase()),
        piecesInPack: this.piecesInPack(),
        isVariableWeight: this.isVariableWeight(),
        code: this.code().trim() || null,
      })
      .subscribe({
        next: (product) => {
          this.saving.set(false);
          this.created.emit(product);
        },
        error: (err: Error) => {
          this.saving.set(false);
          this.saveError.set(err.message || 'Založení zboží se nepovedlo, zkus to prosím znovu.');
        },
      });
  }
}
