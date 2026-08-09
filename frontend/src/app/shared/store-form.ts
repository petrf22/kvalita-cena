import { Component, EventEmitter, Output, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { GeocodeCandidate, Store, UpdateStoreInput } from '../models/catalog';
import { StoreService } from '../services/store-service';
import { LocationMap } from './location-map';

const SIMILAR_CHECK_DEBOUNCE_MS = 400;

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
    NzAlertModule,
    LocationMap,
  ],
  templateUrl: './store-form.html',
  styleUrl: './store-form.css',
})
export class StoreForm {
  private readonly storeService = inject(StoreService);

  /** Nastavený vstup přepne formulář do režimu editace téhle provozovny. */
  readonly store = input<Store | null>(null);

  @Output() readonly created = new EventEmitter<Store>();
  @Output() readonly cancelled = new EventEmitter<void>();

  protected readonly name = signal('');
  protected readonly street = signal('');
  protected readonly city = signal('');
  protected readonly postalCode = signal('');
  protected readonly ico = signal('');

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
      // Store (GraphQL) nevrací osmRef zvoleného kandidáta (jen core.store.osm_ref interně),
      // takže se u editace nedá obnovit "vybraný kandidát" — jen souřadnice samotné. Dokud
      // uživatel nehne se značkou na mapě, uloží se zpátky jako COMMUNITY (viz submit());
      // menší nepřesnost v provenienci, ne v samotné poloze.
      this.manualLat.set(store.lat);
      this.manualLon.set(store.lon);
    });
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
    const ico = this.ico().trim();
    if (!/^\d{8}$/.test(ico)) {
      this.icoError.set('IČO musí mít 8 číslic.');
      return;
    }
    this.icoLoading.set(true);
    this.icoError.set(null);
    this.storeService.companyByIco(ico).subscribe({
      next: (company) => {
        this.icoLoading.set(false);
        if (!company) {
          this.icoError.set('V ARES jsme tohle IČO nenašli.');
          return;
        }
        if (!this.name().trim()) this.name.set(company.name);
        if (!this.street().trim() && company.street) this.street.set(company.street);
        if (!this.city().trim() && company.city) this.city.set(company.city);
        if (!this.postalCode().trim() && company.postalCode) this.postalCode.set(company.postalCode);
      },
      error: () => {
        this.icoLoading.set(false);
        this.icoError.set('Dotaz do ARES se nepovedl, zkus to prosím znovu.');
      },
    });
  }

  geocode(): void {
    if (!this.city().trim()) return;
    this.geocoding.set(true);
    this.manualLat.set(null);
    this.manualLon.set(null);
    this.selectedCandidateRef.set(null);
    this.storeService.geocode(this.street().trim() || null, this.city().trim(), this.postalCode().trim() || null).subscribe({
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
            if (!this.postalCode().trim() && result.postalCode) this.postalCode.set(result.postalCode);
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
    return ico === '' || /^\d{8}$/.test(ico);
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
      ? this.storeService.update(editingStore.id, this.buildUpdateInput(lat, lon, geoSource, osmRef))
      : this.storeService.create({
          name: this.name().trim(),
          street: this.street().trim() || null,
          city: this.city().trim(),
          postalCode: this.postalCode().trim() || null,
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
      error: (err: Error) => {
        this.saving.set(false);
        this.saveError.set(
          err.message ||
            (editingStore
              ? 'Uložení změn se nepovedlo, zkus to prosím znovu.'
              : 'Založení obchodu se nepovedlo, zkus to prosím znovu.'),
        );
      },
    });
  }

  /** Patch nad core.store_user_edit — prázdné pole, které dřív mělo hodnotu, se pošle jako "vymazat". */
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
      ico: this.ico().trim() || null,
      clearIco: this.ico().trim() === '',
      lat,
      lon,
      geoSource,
      osmRef,
    };
  }
}
