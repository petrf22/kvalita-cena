import { Component, EventEmitter, Output, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { Store } from '../models/catalog';
import { AuthService } from '../services/auth-service';
import { StoreService } from '../services/store-service';
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
  imports: [FormsModule, NzSelectModule, NzButtonModule, NzIconModule, NzModalModule, StoreForm],
  templateUrl: './store-picker.html',
  styleUrl: './store-picker.css',
})
export class StorePicker {
  private readonly storeService = inject(StoreService);
  protected readonly auth = inject(AuthService);

  readonly selectedStoreId = input<string | null>(null);
  @Output() readonly selectedStoreIdChange = new EventEmitter<string | null>();

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
  }

  findNearby(): void {
    if (!navigator.geolocation) {
      this.locationError.set('Tento prohlížeč neumí zjistit polohu — vyber obchod ručně.');
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
            else this.locationError.set('V okolí jsme nenašli žádný obchod.');
          },
          error: () => {
            this.locationError.set('Nepodařilo se najít obchody v okolí.');
            this.locating.set(false);
          },
        });
      },
      () => {
        this.locationError.set('Přístup k poloze byl odmítnut — vyber obchod ručně.');
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
