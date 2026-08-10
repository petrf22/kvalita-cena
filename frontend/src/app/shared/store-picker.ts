import { Component, EventEmitter, Output, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoDirective, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { Store } from '../models/catalog';
import { AuthService } from '../services/auth-service';
import { StoreService } from '../services/store-service';
import { translateError } from './error-message';
import { StoreForm } from './store-form';

const SEARCH_DEBOUNCE_MS = 300;

/**
 * Výběr obchodu při zápisu ceny — kombinuje tři cesty, jak se k obchodu dostat, aby fungoval
 * i doma bez sdílené polohy nebo zpětného zápisu (docs/datovy-model.md, "Identita provozovny"):
 * napsat název/město (searchStores), stisknout "Najít v okolí" (nearbyStores, dosavadní
 * chování z product-detail-page) nebo založit nový obchod v modalu. Mobilní protějšek:
 * mobile ui/common/StorePicker.kt.
 */
@Component({
  selector: 'app-store-picker',
  imports: [
    FormsModule,
    NzSelectModule,
    NzButtonModule,
    NzIconModule,
    NzModalModule,
    StoreForm,
    TranslocoDirective,
  ],
  providers: [provideTranslocoScope('store')],
  templateUrl: './store-picker.html',
  styleUrl: './store-picker.css',
})
export class StorePicker {
  private readonly storeService = inject(StoreService);
  private readonly transloco = inject(TranslocoService);
  protected readonly auth = inject(AuthService);

  readonly selectedStoreId = input<string | null>(null);
  @Output() readonly selectedStoreIdChange = new EventEmitter<string | null>();
  /** Celý objekt vedle ID — price-entry-page ho potřebuje kvůli store.country (měna zápisu). */
  @Output() readonly selectedStoreChange = new EventEmitter<Store | null>();

  protected readonly suggestions = signal<Store[]>([]);
  protected readonly selectedStore = signal<Store | null>(null);
  protected readonly searching = signal(false);
  protected readonly locating = signal(false);
  protected readonly locationError = signal<string | null>(null);
  protected readonly showAddModal = signal(false);

  protected readonly displayOptions = computed(() => {
    const list = [...this.suggestions()];
    const selected = this.selectedStore();
    if (selected && !list.some((s) => s.id === selected.id)) list.unshift(selected);
    return list;
  });

  private searchTimer?: ReturnType<typeof setTimeout>;

  onSearch(query: string): void {
    clearTimeout(this.searchTimer);
    if (!query.trim()) {
      this.suggestions.set([]);
      return;
    }
    this.searchTimer = setTimeout(() => {
      this.searching.set(true);
      this.storeService.search(query.trim(), null, 20).subscribe({
        next: (result) => {
          this.suggestions.set(result.items);
          this.searching.set(false);
        },
        error: () => this.searching.set(false),
      });
    }, SEARCH_DEBOUNCE_MS);
  }

  onSelectId(id: string | null): void {
    const store = this.displayOptions().find((s) => s.id === id) ?? null;
    this.selectedStore.set(store);
    this.selectedStoreIdChange.emit(id);
    this.selectedStoreChange.emit(store);
  }

  findNearby(): void {
    if (!navigator.geolocation) {
      this.locationError.set(this.transloco.translate('store.picker.geoUnsupported'));
      return;
    }
    this.locating.set(true);
    this.locationError.set(null);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        this.storeService.nearby(position.coords.latitude, position.coords.longitude).subscribe({
          next: (stores) => {
            this.suggestions.set(stores);
            this.locating.set(false);
            if (stores.length > 0) this.onSelectId(stores[0].id);
            else this.locationError.set(this.transloco.translate('store.picker.noNearbyStores'));
          },
          error: (err) => {
            this.locationError.set(translateError(err, this.transloco));
            this.locating.set(false);
          },
        });
      },
      () => {
        this.locationError.set(this.transloco.translate('store.picker.geoPermissionDenied'));
        this.locating.set(false);
      },
    );
  }

  onStoreCreated(store: Store): void {
    this.showAddModal.set(false);
    this.suggestions.update((list) => [store, ...list]);
    this.onSelectId(store.id);
  }
}
