import { Component, EventEmitter, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { GeocodeCandidate, Store } from '../models/catalog';
import { StoreService } from '../services/store-service';

const SIMILAR_CHECK_DEBOUNCE_MS = 400;

/**
 * Založení provozovny — pro zápis ceny bez sdílení polohy nebo zpětně z domova
 * (docs/datovy-model.md, "Identita provozovny"). Používá se uvnitř modalu ze StorePicker.
 * Mobilní protějšek: mobile ui/store/StoreFormScreen.kt.
 */
@Component({
  selector: 'app-store-form',
  imports: [FormsModule, NzFormModule, NzInputModule, NzButtonModule, NzIconModule, NzRadioModule, NzAlertModule],
  templateUrl: './store-form.html',
  styleUrl: './store-form.css',
})
export class StoreForm {
  private readonly storeService = inject(StoreService);

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
  // tvrdou pojistku (uq_store_identity), tohle je jen včasné varování uživateli.
  protected readonly similarStores = signal<Store[]>([]);
  private similarCheckTimer?: ReturnType<typeof setTimeout>;

  protected readonly geocoding = signal(false);
  protected readonly geocodeCandidates = signal<GeocodeCandidate[]>([]);
  protected readonly geocodeAttribution = signal<string | null>(null);
  protected readonly selectedCandidateRef = signal<GeocodeCandidate | null>(null);
  protected readonly manualLat = signal<number | null>(null);
  protected readonly manualLon = signal<number | null>(null);

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  onNameOrCityChange(): void {
    clearTimeout(this.similarCheckTimer);
    if (!this.name().trim() || !this.city().trim()) {
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
    navigator.geolocation.getCurrentPosition(
      (position) => {
        this.manualLat.set(position.coords.latitude);
        this.manualLon.set(position.coords.longitude);
        this.selectedCandidateRef.set(null);
      },
      () => {
        // Odmítnutí přístupu k poloze — obchod jde uložit i bez souřadnic, viz šablona.
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
    this.storeService
      .create({
        name: this.name().trim(),
        street: this.street().trim() || null,
        city: this.city().trim(),
        postalCode: this.postalCode().trim() || null,
        ico: this.ico().trim() || null,
        lat: candidate?.lat ?? this.manualLat(),
        lon: candidate?.lon ?? this.manualLon(),
        geoSource: candidate ? 'OSM' : this.manualLat() != null ? 'COMMUNITY' : null,
        osmRef: candidate?.osmRef ?? null,
      })
      .subscribe({
        next: (store) => {
          this.saving.set(false);
          this.created.emit(store);
        },
        error: (err: Error) => {
          this.saving.set(false);
          this.saveError.set(err.message || 'Založení obchodu se nepovedlo, zkus to prosím znovu.');
        },
      });
  }
}
