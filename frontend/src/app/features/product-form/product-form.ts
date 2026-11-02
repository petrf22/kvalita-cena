import {
  Component,
  EventEmitter,
  Input,
  Output,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
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
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTreeSelectModule } from 'ng-zorro-antd/tree-select';
import { ExternalProductCandidate, Product, ProductSummary, Store } from '../../models/catalog';
import type { CategoriesQuery } from '../../models/generated/graphql';
import { CountryService } from '../../services/country-service';
import { FormatService } from '../../services/format-service';
import {
  AVAILABLE_LANGS,
  INTL_TAGS,
  LanguageService,
  isAppLang,
} from '../../services/language-service';
import { MediaService } from '../../services/media-service';
import { ProductService } from '../../services/product-service';
import { ReceiptService } from '../../services/receipt-service';
import { buildCategoryTree, categoryBreadcrumb } from '../../shared/category-tree';
import { translateError } from '../../shared/error-message';
import { NET_CONTENT_UOM_KEYS } from '../../shared/enum-labels';
import { normalizeCode } from '../../shared/gtin';
import { PhotoSlot } from '../../shared/photo-slot';
import {
  NET_CONTENT_UOM_CHOICES,
  NetContentUomChoice,
  OffCandidateDefaults,
  VisibleNetContent,
  ProductFormDefaults,
  buildUpdateProductInput,
  changedFromOff,
  changedNames,
  codeMatchesOffCandidate,
  isProductFormValid,
  netContentForOffSubmit,
  offCandidateDefaults,
  pendingPhotoUploads,
  productFormDefaults,
  visibleNetContent,
  isNetContentUomChoice,
} from './product-form-validation';
import { BarcodeScanner } from './barcode-scanner';

type CategoryOption = CategoriesQuery['categories'][number];

const SUGGESTIONS_DEBOUNCE_MS = 300;

export interface ExistingProductMatch {
  product: Product;
  alias: string;
}

/**
 * Založení zboží — s naskenovaným EANem i bez něj. Bezkódové zboží (žádný kód na obalu, jen
 * "pečivo za 45 Kč" na účtence, nebo podniková prodejna zemědělského družstva bez EANu) vznikne
 * jako druhová položka (docs/reputace.md, "Zboží bez čárového kódu") — server ji založí jako
 * DRAFT/isGeneric a confidence zastropuje na MEDIUM, appka tu nic z toho neřeší. Mobilní
 * protějšek: mobile ui/product/ProductFormScreen.kt.
 *
 * Se vstupem `product` přejde do režimu editace existujícího zboží (patch nad
 * core.product_user_edit, `updateProduct`) — používá ji `features/product-detail`, stejný
 * princip jako `shared/store-form.ts`. V editaci appka nenabízí návrhy podobných položek (zboží
 * už existuje) ani fotoslots (fotky se spravují v galerii na detailu) a čárový kód je jen ke
 * čtení — `UpdateProductInput` ho neumí měnit.
 */
@Component({
  selector: 'app-product-form',
  imports: [
    FormsModule,
    NgTemplateOutlet,
    NzFormModule,
    NzInputModule,
    NzInputNumberModule,
    NzTreeSelectModule,
    NzSelectModule,
    NzSwitchModule,
    NzButtonModule,
    NzAlertModule,
    NzIconModule,
    NzModalModule,
    TranslocoDirective,
    TranslocoPipe,
    PhotoSlot,
    BarcodeScanner,
  ],
  providers: [provideTranslocoScope('product-form')],
  templateUrl: './product-form.html',
  styleUrl: './product-form.css',
})
export class ProductForm {
  private readonly productService = inject(ProductService);
  private readonly receiptService = inject(ReceiptService);
  private readonly mediaService = inject(MediaService);
  protected readonly format = inject(FormatService);
  private readonly transloco = inject(TranslocoService);
  private readonly language = inject(LanguageService);
  private readonly country = inject(CountryService);

  /**
   * Kód známý předem — zadaný v `price-entry-page`, nebo EAN z řádku účtenky. Předvyplní pole
   * kódu a zeptá se katalogu i Open Food Facts stejně jako skener (`lookUpCode`; cache
   * v `ProductService`, druhé volání po `price-entry-page` je zdarma). Výpadek/nedostupnost OFF
   * je tichý no-op, formulář zůstane ručně vyplnitelný.
   */
  @Input() set barcode(value: string | null | undefined) {
    const code = value?.trim();
    if (code) this.lookUpCode(code, true);
  }

  /** Nastavený vstup přepne formulář do režimu editace tohohle zboží. */
  readonly product = input<Product | null>(null);
  /** Obchod vybraný před formulářem; pro bezkódový produkt je povinnou součástí identity. */
  readonly store = input<Store | null>(null);
  /**
   * Předvyplněný název při zakládání — text řádku účtenky (`features/receipts`). Zkratka typu
   * „ČF ŽAMPIONY 300G" je jen výchozí bod k přepsání, proto se hned podle ní hledají podobné
   * existující položky (`onNameChange`), ať nevznikne duplicita.
   */
  readonly initialName = input<string | null>(null);
  /**
   * Řádek účtenky, ze kterého se zboží zakládá — formulář si podle něj stáhne předvyplnění
   * kategorie a gramáže (`receiptLineProductDefaults`, docs/ai.md, „Návrh kategorie
   * a jednotky"). Open Food Facts má přednost: co dodá, přepíše předvyplnění z řádku.
   */
  readonly receiptLineId = input<string | null>(null);

  /** Kategorii předvyplnil lokální model — pod polem je poznámka, ať ji uživatel zkontroluje. */
  protected readonly categorySuggested = signal(false);
  /** Jednotku gramáže předvyplnil lokální model, číslo nechal na uživateli. */
  protected readonly unitSuggested = signal(false);
  /** Pole vyplněná z řádku účtenky — OFF kandidát je smí přepsat (`applyOffCandidate`). */
  private readonly filledFromReceipt = new Set<'category' | 'netContent'>();

  @Output() readonly created = new EventEmitter<Product>();
  @Output() readonly existingMatched = new EventEmitter<ExistingProductMatch>();
  @Output() readonly cancelled = new EventEmitter<void>();

  protected readonly name = signal('');
  /**
   * Uživatel název sám psal — předvyplněný text (řádek účtenky, `initialName`) se pak smí
   * přepsat názvem z Open Food Facts (po skenu i z předaného kódu), ručně napsaný ne.
   */
  private nameTouched = false;

  /** Otevřený skener čárového kódu (`BarcodeScanner`) u prázdného pole kódu. */
  protected readonly scanning = signal(false);
  /**
   * Zboží s kódem z pole už v katalogu je — nabídne se místo zakládání duplicity. `fromInput`
   * říká, odkud kód přišel: zvenku (vstup `barcode`, třeba EAN z řádku účtenky) se nabídka
   * ukáže nahoře, kam uživatel kouká hned po otevření formuláře; po skenu pod polem kódu,
   * kde zrovna je. U účtenky je to běžný případ — server páruje podle kódu jen s vybraným
   * obchodem a zboží mohlo přibýt až po zpracování.
   */
  protected readonly existingByCode = signal<{ product: Product; fromInput: boolean } | null>(null);

  /**
   * Pole „Název" je VŽDY v jazyce appky (docs/lokalizace.md) — proto se do něj nikdy nedosazuje
   * cizojazyčný název z OFF. Ostatní jazyky mají vlastní, sbalenou sekci; `sourceNames` drží,
   * jak vypadaly na začátku, aby se serveru posílalo jen to, co uživatel opravdu změnil
   * (u OFF hodnot je to podmínka ODbL, ne úspora — viz `changedNames`).
   */
  protected readonly nameLang = computed(() => this.language.lang());

  /**
   * Jazyk zvolené ZEMĚ, když se liší od jazyka appky — jediný další jazyk, který formulář
   * nabízí sám. Kdo má appku česky a nakupuje na Slovensku, opisuje z obalu slovenský název;
   * angličtina/polština/němčina jsou v takové chvíli šum a schovají se za „Další jazyky".
   * Zdroj je `CountryInfo.defaultLocale` ze serveru (`app.i18n.country-locale`), ne vlastní
   * kopie mapy — než dotaz doběhne, je `null` a sekce se chová jako dřív.
   */
  protected readonly countryLang = computed(() => {
    const lang = this.country
      .countries()
      .find((c) => c.code === this.country.country())?.defaultLocale;
    return lang && lang !== this.nameLang() && isAppLang(lang) ? lang : null;
  });
  /** Zbylé jazyky za druhým rozkliknutím — jazyk appky ani jazyk země mezi nimi znovu nejsou. */
  protected readonly moreLangs = computed(() =>
    AVAILABLE_LANGS.filter((l) => l !== this.nameLang() && l !== this.countryLang()),
  );
  private readonly otherLangs = computed(() =>
    AVAILABLE_LANGS.filter((l) => l !== this.nameLang()),
  );
  protected readonly otherNames = signal<Record<string, string>>({});
  protected readonly otherNamesExpanded = signal(false);
  protected readonly moreLangsExpanded = signal(false);
  private sourceNames: Record<string, string> = {};

  /** Název, který zboží zatím má jen v cizím jazyce — podklad pro upozornění nad formulářem. */
  protected readonly foreignNameHint = computed(() => {
    if (this.name().trim() !== '') return null;
    const filled = this.otherLangs()
      .map((lang) => ({ lang, name: this.otherNames()[lang] ?? '' }))
      .filter((entry) => entry.name !== '');
    return filled.length > 0 ? filled[0] : null;
  });

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
  protected readonly netContentValue = signal<number | null>(null);
  /**
   * Jednotka, ve které uživatel gramáž/objem zadává — vybírá se z comboboxu PŘED číslem, ať jde
   * opsat „60 g" z obalu místo přepočítávání na 0,06 kg. Do serveru jde v páru s hodnotou,
   * `net_content_base` (kg/l) si z dvojice dopočítá sám.
   *
   * `null` je „—", tedy gramáž nevyplněná = cena platí za balení. Zároveň je to jediný vstup,
   * ze kterého vzniká základní jednotka (`unitBaseForUom`) — formulář se na ni od 2026-09
   * neptá zvlášť, viz `visibleNetContent`.
   */
  protected readonly netContentUom = signal<NetContentUomChoice | null>(null);
  protected readonly netContentUomKeys = NET_CONTENT_UOM_KEYS;
  protected readonly netContentUomChoices = NET_CONTENT_UOM_CHOICES;
  /** Krok šipek podle jednotky — v gramech/mililitrech se zadávají celá čísla (60 g, 330 ml),
   *  v kilogramech/litrech desetiny (0,5 kg). */
  protected readonly netContentStep = computed(() =>
    this.netContentUom() === 'G' || this.netContentUom() === 'ML' ? 1 : 0.05,
  );
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
  /** Snímek předvyplněných hodnot (gramáž i s jednotkou z OFF) — jen appka sama, ne pro šablonu. */
  private offDefaults: OffCandidateDefaults | null = null;

  /** Snímek prefillu z `product()` pro editaci — obdoba `offDefaults`, jiný zdroj. */
  private editDefaults: ProductFormDefaults | null = null;

  constructor() {
    this.productService.categories().subscribe({
      next: (categories) => this.categories.set(categories),
      // Formulář jde vyplnit i bez číselníku — jen se pak nedá uložit (kategorie je povinná).
      error: () => {},
    });

    effect(() => {
      const initial = this.initialName()?.trim();
      if (!initial || this.product()) return;
      untracked(() => {
        if (this.name() === '') this.onNameChange(initial);
      });
    });

    effect(() => {
      const lineId = this.receiptLineId();
      if (!lineId || this.product()) return;
      untracked(() =>
        this.receiptService.lineProductDefaults(lineId).subscribe({
          next: (defaults) => {
            if (defaults) this.applyReceiptDefaults(defaults);
          },
          // Bez předvyplnění jde formulář vyplnit ručně — výpadek je tichý.
          error: () => {},
        }),
      );
    });

    effect(() => {
      const product = this.product();
      if (!product) return;
      const defaults = productFormDefaults(product, this.nameLang());
      this.editDefaults = defaults;
      this.sourceNames = defaults.names;
      this.otherNames.set({ ...defaults.names });
      this.name.set(defaults.name);
      this.brandName.set(defaults.brandName);
      this.selectedCategoryId.set(defaults.categoryId);
      this.netContentValue.set(defaults.netContentValue);
      this.netContentUom.set(defaults.netContentUom);
      this.piecesInPack.set(defaults.piecesInPack);
      this.isVariableWeight.set(defaults.isVariableWeight);
      this.code.set(product.gtin ?? '');
    });
  }

  onOtherNameChange(lang: string, value: string): void {
    this.otherNames.update((names) => ({ ...names, [lang]: value }));
  }

  protected otherName(lang: string): string {
    return this.otherNames()[lang] ?? '';
  }

  toggleOtherNames(): void {
    this.otherNamesExpanded.update((expanded) => !expanded);
  }

  toggleMoreLangs(): void {
    this.moreLangsExpanded.update((expanded) => !expanded);
  }

  /** Psaní do pole „Název" (šablona) — na rozdíl od `onNameChange` i značka ručního zásahu. */
  onNameInput(value: string): void {
    this.nameTouched = true;
    this.onNameChange(value);
  }

  onNameChange(value: string): void {
    this.name.set(value);
    clearTimeout(this.suggestionsTimer);
    // V režimu editace nedává nabídka podobných položek smysl — zboží už existuje.
    if (this.product() || !value.trim()) {
      this.suggestions.set([]);
      return;
    }
    this.suggestionsTimer = setTimeout(() => {
      this.suggestionsLoading.set(true);
      this.productService.suggestions(value.trim(), this.store()?.id ?? null).subscribe({
        next: (result) => {
          this.suggestions.set(result);
          this.suggestionsLoading.set(false);
        },
        error: () => this.suggestionsLoading.set(false),
      });
    }, SUGGESTIONS_DEBOUNCE_MS);
  }

  /**
   * Předvyplní formulář z OFF kandidáta — gramáž i s jednotkou bere, jak je
   * (`offCandidateDefaults`, past OFF kandidáta), a snímek pro submit() si uloží stranou do
   * `offDefaults`. Pole, která kandidát nemá, nechá prázdná pro ruční vyplnění.
   *
   * Co uživatel už vyplnil, OFF nepřepíše — `offDefaults` zůstává snímkem OFF, takže se jeho
   * hodnota pošle jako vlastní změna. Výjimka je předvyplněný název (`initialName`, zkratka
   * z účtenky typu „NAKL. OKURKY 680G"), který uživatel nepsal: ten název z OFF v jazyce appky
   * nahradí. Když ho OFF v jazyce appky nemá, zkratka zůstane — cizojazyčný název se do pole
   * nikdy nedosazuje (docs/lokalizace.md).
   */
  /**
   * Předvyplnění z řádku účtenky — jen prázdná pole a nic, co už dodal OFF kandidát. Gramáž
   * vždy jako dvojice; u návrhu modelu jen jednotka bez čísla, číslo opíše uživatel z obalu.
   */
  private applyReceiptDefaults(defaults: {
    category?: { id: string } | null;
    categorySource?: string | null;
    netContentValue?: number | null;
    netContentUom?: string | null;
    isVariableWeight: boolean;
    netContentSource?: string | null;
  }): void {
    const off = this.offDefaults;
    const offHasNetContent = off?.netContentUom != null;
    if (!offHasNetContent && !this.isVariableWeight() && this.netContentUom() === null) {
      if (defaults.isVariableWeight) {
        this.isVariableWeight.set(true);
        this.filledFromReceipt.add('netContent');
      } else if (isNetContentUomChoice(defaults.netContentUom)) {
        this.netContentUom.set(defaults.netContentUom);
        this.netContentValue.set(defaults.netContentValue ?? null);
        this.unitSuggested.set(defaults.netContentSource === 'AI');
        this.filledFromReceipt.add('netContent');
      }
    }
    const category = defaults.category;
    if (category && !off?.categoryId && this.selectedCategoryId() === null) {
      this.selectedCategoryId.set(category.id);
      this.categorySuggested.set(defaults.categorySource === 'AI');
      this.filledFromReceipt.add('category');
    }
  }

  /** Kategorie vybraná uživatelem — návrh modelu tím přestává být návrhem. */
  protected onCategoryChosen(id: string | null): void {
    this.categorySuggested.set(false);
    this.filledFromReceipt.delete('category');
    this.selectedCategoryId.set(id);
  }

  /**
   * Gramáž/objem a přepínač váhového zboží jsou jedna dvojice — jakákoli ruční změna kterékoli
   * části z nich udělá vlastní hodnotu uživatele. Předvyplnění z řádku účtenky pak už nesmí
   * přepsat pozdě dorazivší OFF kandidát (`applyOffCandidate`).
   */
  private claimNetContent(): void {
    this.unitSuggested.set(false);
    this.filledFromReceipt.delete('netContent');
  }

  /** Jednotka vybraná uživatelem. */
  protected onNetContentUomChosen(uom: NetContentUomChoice | null): void {
    this.claimNetContent();
    this.netContentUom.set(uom);
  }

  /** Číslo gramáže napsané uživatelem. */
  protected onNetContentValueChosen(value: number | null): void {
    this.claimNetContent();
    this.netContentValue.set(value);
  }

  /** Přepínač váhového zboží přepnutý uživatelem. */
  protected onVariableWeightChosen(value: boolean): void {
    this.claimNetContent();
    this.isVariableWeight.set(value);
  }

  private applyOffCandidate(candidate: ExternalProductCandidate): void {
    this.offCandidate.set(candidate);
    const defaults = offCandidateDefaults(candidate, this.nameLang());
    this.offDefaults = defaults;
    this.sourceNames = defaults.names;
    this.otherNames.set({ ...defaults.names });
    // Sekce ostatních jazyků se ZÁMĚRNĚ nerozbaluje sama, ani když OFF zná zboží jen
    // cizojazyčně — ten název je vidět v upozornění pod polem „Název" a rozbalená sekce by
    // místo toho ukázala hlavně prázdná pole jazyků, které nikdo nevyplní.
    if (defaults.name && !(this.nameTouched && this.name().trim() !== '')) {
      this.name.set(defaults.name);
    }
    if (defaults.brandName && this.brandName().trim() === '') {
      this.brandName.set(defaults.brandName);
    }
    // Co předvyplnil řádek účtenky, OFF přepíše — kategorie a gramáž z OFF jsou přesnější
    // než soused v katalogu nebo model, a hodnota shodná s OFF se pak neodešle jako vlastní.
    const categoryFromReceipt = this.filledFromReceipt.has('category');
    const netContentFromReceipt = this.filledFromReceipt.has('netContent');
    if (defaults.categoryId && (this.selectedCategoryId() === null || categoryFromReceipt)) {
      this.selectedCategoryId.set(defaults.categoryId);
      this.categorySuggested.set(false);
      this.filledFromReceipt.delete('category');
    }
    if (netContentFromReceipt && defaults.netContentUom) {
      this.isVariableWeight.set(false);
      this.netContentValue.set(null);
      this.netContentUom.set(null);
      this.unitSuggested.set(false);
      this.filledFromReceipt.delete('netContent');
    }
    if (defaults.netContentValue != null && this.netContentValue() === null) {
      this.netContentValue.set(defaults.netContentValue);
      if (defaults.netContentUom) this.netContentUom.set(defaults.netContentUom);
    } else if (defaults.netContentUom && this.netContentUom() === null) {
      this.netContentUom.set(defaults.netContentUom);
    }
  }

  /** Kód přečtený skenerem — stejná cesta jako vstup `barcode`, jen nabídka je u pole kódu. */
  onScanned(code: string): void {
    this.scanning.set(false);
    this.lookUpCode(code, false);
  }

  /** Ruční přepis pole kódu — nabídka existujícího zboží patřila k předchozímu kódu. */
  onCodeChange(value: string): void {
    this.code.set(value);
    this.existingByCode.set(null);
  }

  /**
   * Vyplní pole kódu a zeptá se katalogu i Open Food Facts: existující zboží se nabídne
   * k použití místo založení duplicity, OFF kandidát předvyplní prázdná pole. Jediná cesta
   * pro vstup `barcode` i skener.
   */
  private lookUpCode(code: string, fromInput: boolean): void {
    this.code.set(code);
    this.existingByCode.set(null);
    this.productService.lookupByCode(code).subscribe({
      next: (result) => {
        // Uživatel kód mezitím přepsal — odpověď patří k jinému kódu, než je v poli.
        if (normalizeCode(this.code()) !== normalizeCode(code)) return;
        if (result.status === 'EXISTING' && result.product) {
          this.existingByCode.set({ product: result.product, fromInput });
        } else if (result.status === 'OFF_CANDIDATE' && result.candidate) {
          this.applyOffCandidate(result.candidate);
        }
      },
      // Výpadek katalogu/OFF nevadí — kód je v poli a formulář jde vyplnit ručně.
      error: () => {},
    });
  }

  /** Zboží s kódem z pole už existuje — použít ho místo založení nového. */
  useExistingByCode(): void {
    const product = this.existingByCode()?.product;
    if (product) this.existingMatched.emit({ product, alias: this.name().trim() });
  }

  /** Uživatel si vybral existující nabídnutou položku místo založení nové (docs/reputace.md). */
  useExisting(summary: ProductSummary): void {
    this.saving.set(true);
    this.saveError.set(null);
    this.productService.getById(summary.id).subscribe({
      next: (product) => {
        this.saving.set(false);
        if (product) this.existingMatched.emit({ product, alias: this.name().trim() });
      },
      error: (err) => {
        this.saving.set(false);
        this.saveError.set(translateError(err, this.transloco));
      },
    });
  }

  isValid(): boolean {
    return (
      isProductFormValid(this.name(), this.selectedCategoryId()) &&
      (this.product() != null || this.code().trim().length > 0 || this.store() != null)
    );
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

    const editingProduct = this.product();
    if (editingProduct) {
      this.updateExisting(editingProduct, categoryId);
      return;
    }

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
            nameLang: this.nameLang(),
            names: changedNames(this.otherNames(), this.sourceNames, this.nameLang()),
            brandName: this.brandName().trim() || null,
            categoryId,
            // unitBase chodí ze `submittedNetContent()` — odvozuje se z vybrané jednotky,
            // formulář se na něj neptá vlastní otázkou.
            ...this.submittedNetContent(),
            piecesInPack: this.piecesInPack(),
            storeId: this.store()?.id ?? null,
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
   * Patch nad core.product_user_edit — pole, která uživatel nezměnil oproti `editDefaults`, se
   * posílají jako `null` (`buildUpdateProductInput`, zrcadlo `buildUpdateInput()` ve
   * `shared/store-form.ts`). Fotky se v editaci nenahrávají, ty se spravují v galerii na detailu.
   */
  private updateExisting(product: Product, categoryId: string): void {
    const defaults = this.editDefaults;
    if (!defaults) return;
    const input = buildUpdateProductInput(
      {
        name: this.name(),
        nameLang: this.nameLang(),
        names: changedNames(this.otherNames(), this.sourceNames, this.nameLang()),
        brandName: this.brandName(),
        categoryId,
        ...this.submittedNetContent(),
        piecesInPack: this.piecesInPack(),
      },
      defaults,
    );
    this.productService.updateProduct(product.id, input).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.created.emit(updated);
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
        // Jazyk obalu na fotce = jazyk appky: uživatel fotí to balení, které má v ruce,
        // a u etikety (LABEL) je jazyk podstata věci — je to fotka textu složení.
        this.mediaService
          .upload('PRODUCT', productId, upload.file, null, upload.kind, this.nameLang())
          .pipe(
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
    const visible = this.submittedNetContent();
    const netContent = netContentForOffSubmit(visible, defaults);
    return this.productService.createProductFromOff({
      code: candidate.code,
      name: text.name,
      nameLang: this.nameLang(),
      names: changedNames(this.otherNames(), this.sourceNames, this.nameLang()),
      brandName: text.brandName,
      categoryId: text.categoryId,
      unitBase: visible.unitBase,
      netContentValue: netContent.netContentValue,
      netContentUom: netContent.netContentUom,
      piecesInPack: this.piecesInPack(),
      isVariableWeight: visible.isVariableWeight,
    });
  }

  /**
   * Gramáž/objem + váhové zboží tak, jak smí odejít do serveru — očištěné o pole, která
   * formulář zrovna neukazuje (`visibleNetContent`). Jediná cesta, kterou tahle trojice do
   * submitu chodí; sáhnout na signály přímo by propustilo hodnotu zbylou po přepnutí jednotky.
   */
  private submittedNetContent(): VisibleNetContent {
    return visibleNetContent({
      netContentValue: this.netContentValue(),
      netContentUom: this.netContentUom(),
      isVariableWeight: this.isVariableWeight(),
      // U váhového zboží formulář jednotku neukazuje, takže hmotnost/objem se u editace musí
      // vzít z uloženého zboží — jinak by se rozlévané víno tiše překlopilo na hmotnost.
      storedUnitBase: this.editDefaults?.unitBase ?? null,
    });
  }
}
