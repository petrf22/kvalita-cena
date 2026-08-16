import {
  Component,
  EventEmitter,
  Output,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
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
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { GeocodeCandidate, Store, UpdateStoreInput } from '../models/catalog';
import { CountryService } from '../services/country-service';
import { StoreService } from '../services/store-service';
import { translateError } from './error-message';
import { KNOWN_COUNTRIES } from './known-countries';
import { LocationMap } from './location-map';

const SIMILAR_CHECK_DEBOUNCE_MS = 400;

/**
 * Tvar identifikačního čísla firmy per zemi (docs/lokalizace.md) — zrcadlí
 * CompanyIdValidator/CompanyIdValidators na backendu (jen tvar, ne kontrolní součet —
 * ten appka nekontroluje, aby neduplikovala algoritmus). Země bez záznamu = appka
 * tvar nekontroluje vůbec, stejně jako backend hodnotu bez kontroly jen uloží.
 */
const COMPANY_ID_DIGITS: Record<string, number> = { CZ: 8, SK: 8, PL: 10 };
/** AresService je zatím jediný napojený rejstřík (CompanyRegistry na backendu) — jen pro CZ. */
const COUNTRIES_WITH_REGISTRY: readonly string[] = ['CZ'];

/**
 * Založení provozovny — pro zápis ceny bez sdílení polohy nebo zpětně z domova
 * (docs/datovy-model.md, "Identita provozovny"). Používá se uvnitř modalu ze StorePicker.
 *
 * Se vstupem `store` přejde do režimu editace existující provozovny (patch nad
 * core.store_user_edit, `updateStore`) — používá ji `features/store-detail`. Mobilní
 * protějšek: mobile ui/store/StoreFormScreen.kt.
 */
@Component({
  selector: 'app-store-form',
  imports: [
    FormsModule,
    NzFormModule,
    NzInputModule,
    NzButtonModule,
    NzIconModule,
    NzRadioModule,
    NzSelectModule,
    NzAlertModule,
    LocationMap,
    TranslocoDirective,
    TranslocoPipe,
  ],
  providers: [provideTranslocoScope('store')],
  templateUrl: './store-form.html',
  styleUrl: './store-form.css',
})
export class StoreForm {
  private readonly storeService = inject(StoreService);
  private readonly transloco = inject(TranslocoService);
  protected readonly countryService = inject(CountryService);

  /** Nastavený vstup přepne formulář do režimu editace téhle provozovny. */
  readonly store = input<Store | null>(null);

  @Output() readonly created = new EventEmitter<Store>();
  @Output() readonly cancelled = new EventEmitter<void>();

  protected readonly name = signal('');
  protected readonly street = signal('');
  protected readonly city = signal('');
  protected readonly postalCode = signal('');
  protected readonly ico = signal('');

  /**
   * Určuje popisek/tvar IČO-NIP a viditelnost "Načíst z ARES" (docs/lokalizace.md) a je teď i
   * VOLITELNÝ vstup — dřív šla jen natvrdo 'CZ', přepsatelná jen skrz reverseGeocode
   * ("Použít mou polohu"), takže se slovenský/polský obchod založený z domova ukládal jako
   * český (a všem jeho cenám se navěky dosadilo CZK). Výchozí hodnota teď jde z
   * CountryService (viewerova volba v Nastavení), uživatel ji navíc může ručně přepsat ve
   * formuláři — viz country-select v šabloně. V režimu editace jde ze `store()`.
   *
   * Server country na rozdíl od zbytku formuláře gatuje TrustLevelService.isTrusted
   * (docs/lokalizace.md, "Country selector v UI") — má tvrdý dopad na měnu zápisu pro VŠECHNY
   * uživatele, ne jen na to, jak provozovnu vidí autor. Nedůvěryhodný autor dostane chybu ze
   * serveru (translateError níž), formulář to preventivně neomezuje.
   */
  protected readonly country = signal(this.countryService.country());
  protected readonly countryOptions = computed(() => {
    const fromServer = this.countryService.countries().map((c) => c.code);
    return fromServer.length > 0 ? fromServer : KNOWN_COUNTRIES;
  });
  protected readonly companyIdDigits = computed(() => COMPANY_ID_DIGITS[this.country()] ?? null);
  protected readonly hasCompanyRegistry = computed(() =>
    COUNTRIES_WITH_REGISTRY.includes(this.country()),
  );

  protected readonly icoLoading = signal(false);
  protected readonly icoError = signal<string | null>(null);

  // "Našli jsme podobné" — povinný krok před uložením (docs/datovy-model.md), server má navíc
  // tvrdou pojistku (uq_store_identity), tohle je jen včasné varování uživateli. V režimu
  // editace nedává smysl (obchod už existuje), viz onNameOrCityChange().
  protected readonly similarStores = signal<Store[]>([]);
  private similarCheckTimer?: ReturnType<typeof setTimeout>;

  protected readonly geocoding = signal(false);
  protected readonly geocodeCandidates = signal<GeocodeCandidate[]>([]);
  protected readonly geocodeAttribution = signal<string | null>(null);
  protected readonly selectedCandidateRef = signal<GeocodeCandidate | null>(null);
  protected readonly manualLat = signal<number | null>(null);
  protected readonly manualLon = signal<number | null>(null);
  protected readonly locating = signal(false);

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  constructor() {
    effect(() => {
      const store = this.store();
      if (!store) return;
      this.name.set(store.name);
      this.street.set(store.street ?? '');
      this.city.set(store.city);
      this.postalCode.set(store.postalCode ?? '');
      this.ico.set(store.ico ?? '');
      this.country.set(store.country);
      // Store (GraphQL) nevrací osmRef zvoleného kandidáta (jen core.store.osm_ref interně),
      // takže se u editace nedá obnovit "vybraný kandidát" — jen souřadnice samotné. Dokud
      // uživatel nehne se značkou na mapě, uloží se zpátky jako COMMUNITY (viz submit());
      // menší nepřesnost v provenienci, ne v samotné poloze.
      this.manualLat.set(store.lat);
      this.manualLon.set(store.lon);
    });
  }

  // Metoda, ne computed() — translate() není signálově reaktivní na změnu jazyka, appka na ni
  // reaguje přes reRenderOnLangChange (app.config.ts, stejný vzor jako price-chart.ts).
  protected companyIdLabel(): string {
    return this.transloco.translate(`store.companyId.label.${this.country()}`);
  }

  /** Appka zatím zná jména jen pro CZ/SK/PL — chybějící překlad zobrazí rovnou kód země. */
  protected countryOptionLabel(code: string): string {
    const key = `store.form.country.${code}`;
    const translated = this.transloco.translate(key);
    return translated === key ? code : translated;
  }

  /** Aktuálně zvolený bod (kandidát z geokódování, nebo ruční/přenesená poloha) pro mapu. */
  protected currentLat(): number | null {
    return this.selectedCandidateRef()?.lat ?? this.manualLat();
  }

  protected currentLon(): number | null {
    return this.selectedCandidateRef()?.lon ?? this.manualLon();
  }

  onMapPointSelected(point: { lat: number; lon: number }): void {
    this.selectedCandidateRef.set(null);
    this.manualLat.set(point.lat);
    this.manualLon.set(point.lon);
  }

  onNameOrCityChange(): void {
    clearTimeout(this.similarCheckTimer);
    // V režimu editace nedává "našli jsme podobné" smysl — obchod už existuje.
    if (this.store() || !this.name().trim() || !this.city().trim()) {
      this.similarStores.set([]);
      return;
    }
    this.similarCheckTimer = setTimeout(() => {
      this.storeService.search(this.name().trim(), this.city().trim(), 5).subscribe({
        next: (result) => this.similarStores.set(result.items),
        // Kontrola podobných je jen doporučující — chyba dotazu nesmí blokovat založení.
        error: () => {},
      });
    }, SIMILAR_CHECK_DEBOUNCE_MS);
  }

  lookupIco(): void {
    const digits = this.companyIdDigits();
    const shapeOk = digits != null && new RegExp(`^\\d{${digits}}$`).test(this.ico().trim());
    if (!shapeOk) {
      this.icoError.set(
        this.transloco.translate('store.companyId.shapeInvalid', {
          label: this.companyIdLabel(),
          digits,
        }),
      );
      return;
    }
    this.icoLoading.set(true);
    this.icoError.set(null);
    this.storeService.companyByIco(this.ico().trim()).subscribe({
      next: (company) => {
        this.icoLoading.set(false);
        if (!company) {
          this.icoError.set(this.transloco.translate('store.companyId.notFoundInRegistry'));
          return;
        }
        if (!this.name().trim()) this.name.set(company.name);
        if (!this.street().trim() && company.street) this.street.set(company.street);
        if (!this.city().trim() && company.city) this.city.set(company.city);
        if (!this.postalCode().trim() && company.postalCode)
          this.postalCode.set(company.postalCode);
      },
      error: (err) => {
        this.icoLoading.set(false);
        this.icoError.set(translateError(err, this.transloco));
      },
    });
  }

  geocode(): void {
    if (!this.city().trim()) return;
    this.geocoding.set(true);
    this.manualLat.set(null);
    this.manualLon.set(null);
    this.selectedCandidateRef.set(null);
    this.storeService
      .geocode(this.street().trim() || null, this.city().trim(), this.postalCode().trim() || null)
      .subscribe({
        next: (result) => {
          this.geocodeCandidates.set(result.candidates);
          this.geocodeAttribution.set(result.attribution);
          this.geocoding.set(false);
        },
        error: () => {
          this.geocodeCandidates.set([]);
          this.geocoding.set(false);
        },
      });
  }

  selectCandidate(candidate: GeocodeCandidate): void {
    this.selectedCandidateRef.set(candidate);
    this.manualLat.set(null);
    this.manualLon.set(null);
  }

  useMyLocation(): void {
    if (!navigator.geolocation) return;
    this.locating.set(true);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        const lat = position.coords.latitude;
        const lon = position.coords.longitude;
        this.manualLat.set(lat);
        this.manualLon.set(lon);
        this.selectedCandidateRef.set(null);
        // Doplní jen PRÁZDNÁ adresní pole — nepřepisuje, co uživatel už vyplnil (docs/soukromi.md:
        // reverseGeocode jde stejně jako geocodeAddress výhradně ze serveru).
        this.storeService.reverseGeocode(lat, lon).subscribe({
          next: (result) => {
            this.locating.set(false);
            if (!this.street().trim() && result.street) this.street.set(result.street);
            if (!this.city().trim() && result.city) this.city.set(result.city);
            if (!this.postalCode().trim() && result.postalCode)
              this.postalCode.set(result.postalCode);
            // Jen při zakládání — editovaná provozovna svou zemi už má a appka ji přepočtem
            // polohy nepřepisuje (docs/lokalizace.md). Neznámá země (appka umí jen CZ/SK/PL)
            // se ignoruje, zůstane výchozí CZ.
            if (!this.store() && result.country && KNOWN_COUNTRIES.includes(result.country)) {
              this.country.set(result.country);
            }
          },
          error: () => this.locating.set(false),
        });
      },
      () => {
        // Odmítnutí přístupu k poloze — obchod jde uložit i bez souřadnic, viz šablona.
        this.locating.set(false);
      },
    );
  }

  isValid(): boolean {
    return this.name().trim().length > 0 && this.city().trim().length > 0 && this.isIcoShapeValid();
  }

  isIcoShapeValid(): boolean {
    const ico = this.ico().trim();
    if (ico === '') return true;
    const digits = this.companyIdDigits();
    return digits == null || new RegExp(`^\\d{${digits}}$`).test(ico);
  }

  submit(): void {
    if (!this.isValid()) return;
    this.saving.set(true);
    this.saveError.set(null);
    const candidate = this.selectedCandidateRef();
    const lat = candidate?.lat ?? this.manualLat();
    const lon = candidate?.lon ?? this.manualLon();
    const geoSource = candidate ? 'OSM' : lat != null ? 'COMMUNITY' : null;
    const osmRef = candidate?.osmRef ?? null;

    const editingStore = this.store();
    const request = editingStore
      ? this.storeService.update(
          editingStore.id,
          this.buildUpdateInput(lat, lon, geoSource, osmRef),
        )
      : this.storeService.create({
          name: this.name().trim(),
          street: this.street().trim() || null,
          city: this.city().trim(),
          postalCode: this.postalCode().trim() || null,
          country: this.country(),
          ico: this.ico().trim() || null,
          lat,
          lon,
          geoSource,
          osmRef,
        });

    request.subscribe({
      next: (store) => {
        this.saving.set(false);
        this.created.emit(store);
      },
      error: (err) => {
        this.saving.set(false);
        this.saveError.set(translateError(err, this.transloco));
      },
    });
  }

  /**
   * Patch nad core.store_user_edit — prázdné pole, které dřív mělo hodnotu, se pošle jako
   * "vymazat". `country` je výjimka: server ji NEUKLÁDÁ do patche, ale rovnou do globální
   * provozovny (gatováno důvěrou, viz country signál výš) — posílá se pořád, i beze změny,
   * server sám pozná no-op podle rovnosti s aktuální hodnotou.
   */
  private buildUpdateInput(
    lat: number | null,
    lon: number | null,
    geoSource: 'COMMUNITY' | 'OSM' | null,
    osmRef: string | null,
  ): UpdateStoreInput {
    return {
      name: this.name().trim(),
      street: this.street().trim() || null,
      clearStreet: this.street().trim() === '',
      city: this.city().trim(),
      postalCode: this.postalCode().trim() || null,
      clearPostalCode: this.postalCode().trim() === '',
      country: this.country(),
      ico: this.ico().trim() || null,
      clearIco: this.ico().trim() === '',
      lat,
      lon,
      geoSource,
      osmRef,
    };
  }
}
