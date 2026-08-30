import { Component, EventEmitter, Output, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  TranslocoDirective,
  TranslocoPipe,
  TranslocoService,
  provideTranslocoScope,
} from '@jsverse/transloco';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzDatePickerModule } from 'ng-zorro-antd/date-picker';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { PriceKind, PriceObservation, Product, QuantityBasis, Store } from '../models/catalog';
import { FormatService } from '../services/format-service';
import { ProductService } from '../services/product-service';
import { currencyForCountry } from './country-currency';
import { translateError } from './error-message';
import {
  PRICE_KIND_KEYS,
  QUANTITY_BASIS_KEYS,
  SELECTABLE_VARIABLE_WEIGHT_QUANTITY_BASES,
} from './enum-labels';
import {
  arePriceRowsValid,
  availablePriceKinds,
  fromIsoDate,
  newPriceRow,
  toIsoDate,
  toObservationPriceInputs,
  toObservedAtIso,
  type PriceRow,
} from './price-rows';
import { StorePicker } from './store-picker';

/**
 * Celá zápisová cesta ceny — obchod, seznam řádků "(druh ceny, částka)" s "+"/"−" a odeslání
 * — jako jedna sdílená komponenta, ať `price-entry-page` (nejdřív hledá zboží) i
 * `product-detail-page` (zboží už zná) nemají dvě kopie stejného formuláře. Mobilní protějšek:
 * mobile ui/price/PriceEntryScreen.kt.
 */
@Component({
  selector: 'app-price-entry-form',
  imports: [
    FormsModule,
    NzFormModule,
    NzInputNumberModule,
    NzSelectModule,
    NzButtonModule,
    NzIconModule,
    NzAlertModule,
    NzDatePickerModule,
    StorePicker,
    TranslocoDirective,
    TranslocoPipe,
  ],
  providers: [provideTranslocoScope('price-form')],
  templateUrl: './price-entry-form.html',
  styleUrl: './price-entry-form.css',
})
export class PriceEntryForm {
  private readonly productService = inject(ProductService);
  private readonly transloco = inject(TranslocoService);
  protected readonly format = inject(FormatService);

  readonly product = input.required<Product>();
  /** Rodič si obnoví agregované ceny/graf na produktu — viz price-entry-page/product-detail-page. */
  @Output() readonly submitted = new EventEmitter<PriceObservation[]>();

  protected readonly priceKindKeys = PRICE_KIND_KEYS;
  protected readonly quantityBasisKeys = QUANTITY_BASIS_KEYS;
  protected readonly selectableVariableWeightQuantityBases =
    SELECTABLE_VARIABLE_WEIGHT_QUANTITY_BASES;

  protected readonly selectedStoreId = signal<string | null>(null);
  protected readonly selectedStoreCurrency = signal<string>('CZK');
  protected readonly quantityBasis = signal<QuantityBasis>('PACKAGE');
  protected readonly observedAt = signal<Date | null>(null);
  protected readonly rows = signal<PriceRow[]>([newPriceRow(0, [])]);
  private nextRowKey = 1;

  protected readonly submitting = signal(false);
  protected readonly submitSuccess = signal(false);
  protected readonly submitError = signal<string | null>(null);

  protected readonly canSubmit = computed(
    () => this.selectedStoreId() != null && arePriceRowsValid(this.rows()) && !this.submitting(),
  );

  /** observed_at i promoValidFrom nesmí být v budoucnosti — uživatel zapisuje, co už VIDĚL v regále. */
  protected readonly isFutureDate = (date: Date): boolean => date.getTime() > Date.now();

  /**
   * PriceRow drží datum jako ISO string (posílá se přímo v mutaci) — nz-date-picker chce Date.
   * Cache podle ISO stringu drží STEJNOU referenci na Date napříč change-detection cykly —
   * `fromIsoDate()` volaná přímo v šabloně (`[ngModel]="fromIsoDate(row.promoValidTo)"`) by
   * jinak vracela pokaždé nový objekt, i když se ISO string nezměnil. Angular porovnává vstupy
   * direktiv referenční rovností, takže by NgModel na date-pickeru viděl "změnu" při KAŽDÉM
   * CD cyklu a volal `writeValue()` → `markForCheck()` → další CD cyklus → dokola, dokud je
   * kalendářový panel otevřený (CDK overlay si díky pozicování/resize sám plánuje další tik) —
   * v praxi zamrznutí záložky při výběru data platnosti akce.
   */
  private readonly dateCache = new Map<string, Date>();

  protected toDate(iso: string | null): Date | null {
    if (iso == null) return null;
    let date = this.dateCache.get(iso);
    if (!date) {
      date = fromIsoDate(iso)!;
      this.dateCache.set(iso, date);
    }
    return date;
  }

  /** Nabídka druhu ceny pro konkrétní řádek — vyloučí druhy použité v ostatních řádcích. */
  protected kindsForRow(row: PriceRow): readonly PriceKind[] {
    return availablePriceKinds(this.rows(), row.priceKind);
  }

  /** Jen popisek pole "Cena (Kč/€/zł)" — o skutečně uložené měně rozhoduje server (docs/lokalizace.md). */
  onStoreChange(store: Store | null): void {
    this.selectedStoreCurrency.set(currencyForCountry(store?.country));
  }

  addRow(): void {
    this.rows.update((rows) => [...rows, newPriceRow(this.nextRowKey++, rows)]);
  }

  removeRow(key: number): void {
    this.rows.update((rows) => (rows.length > 1 ? rows.filter((row) => row.key !== key) : rows));
  }

  /** Přepnutí druhu ceny vynuluje pole ostatních druhů — ať se do MULTIBUY nezanese cizí částka. */
  updateRowKind(key: number, priceKind: PriceKind): void {
    this.rows.update((rows) =>
      rows.map((row) =>
        row.key === key
          ? {
              ...row,
              priceKind,
              priceAmount: null,
              multibuyQty: null,
              multibuyTotal: null,
              promoValidFrom: null,
              promoValidTo: null,
            }
          : row,
      ),
    );
  }

  updateRowAmount(key: number, priceAmount: number | null): void {
    this.rows.update((rows) =>
      rows.map((row) => (row.key === key ? { ...row, priceAmount } : row)),
    );
  }

  updateRowMultibuyQty(key: number, multibuyQty: number | null): void {
    this.rows.update((rows) =>
      rows.map((row) => (row.key === key ? { ...row, multibuyQty } : row)),
    );
  }

  updateRowMultibuyTotal(key: number, multibuyTotal: number | null): void {
    this.rows.update((rows) =>
      rows.map((row) => (row.key === key ? { ...row, multibuyTotal } : row)),
    );
  }

  updateRowPromoValidFrom(key: number, date: Date | null): void {
    this.rows.update((rows) =>
      rows.map((row) =>
        row.key === key ? { ...row, promoValidFrom: date ? toIsoDate(date) : null } : row,
      ),
    );
  }

  updateRowPromoValidTo(key: number, date: Date | null): void {
    this.rows.update((rows) =>
      rows.map((row) =>
        row.key === key ? { ...row, promoValidTo: date ? toIsoDate(date) : null } : row,
      ),
    );
  }

  submit(): void {
    const product = this.product();
    const storeId = this.selectedStoreId();
    const rows = this.rows();
    if (!storeId || !arePriceRowsValid(rows)) return;

    this.submitting.set(true);
    this.submitError.set(null);
    this.submitSuccess.set(false);
    const observedAt = this.observedAt();
    this.productService
      .submitObservations({
        productId: product.id,
        storeId,
        quantityBasis: product.isVariableWeight ? this.quantityBasis() : 'PACKAGE',
        observedAt: observedAt ? toObservedAtIso(observedAt) : undefined,
        prices: toObservationPriceInputs(rows),
      })
      .subscribe({
        next: (observations) => {
          this.submitting.set(false);
          this.submitSuccess.set(true);
          this.nextRowKey = 1;
          this.rows.set([newPriceRow(0, [])]);
          this.submitted.emit(observations);
        },
        error: (err: unknown) => {
          this.submitting.set(false);
          this.submitError.set(translateError(err, this.transloco));
        },
      });
  }
}
