import {
  Component,
  EventEmitter,
  OnInit,
  Output,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoDirective, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { Store } from '../models/catalog';
import { AuthService } from '../services/auth-service';
import { CountryService } from '../services/country-service';
import { StoreService } from '../services/store-service';
import { LastStoreService } from '../services/last-store-service';
import { translateError } from './error-message';
import { StoreForm } from './store-form';
import { storeLabel } from './store-label';

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
export class StorePicker implements OnInit {
  private readonly storeService = inject(StoreService);
  private readonly transloco = inject(TranslocoService);
  private readonly lastStore = inject(LastStoreService);
  protected readonly auth = inject(AuthService);
  protected readonly countryService = inject(CountryService);
  protected readonly storeLabel = storeLabel;

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
  /**
   * Rozepsaný výběr — karta s vybraným obchodem se schová a vrátí se seznam. Bez toho by po
   * "Najít v okolí" (vybere první nález) nešlo na zbylé obchody v okolí vůbec dosáhnout jinak
   * než vymazáním volby. Mobilní protějšek je expandSignal v SearchableDropdown.kt.
   */
  protected readonly picking = signal(false);

  protected readonly displayOptions = computed(() => {
    const list = [...this.suggestions()];
    const selected = this.selectedStore();
    if (selected && !list.some((s) => s.id === selected.id)) list.unshift(selected);
    return list;
  });

  /** Město a kód země, ale ten jen když se liší od domácí — stejné pravidlo jako storeLabel. */
  protected cityLine(store: Store): string {
    return store.country && store.country !== this.countryService.country()
      ? `${store.city} · ${store.country}`
      : store.city;
  }

  /** Druhý řádek položky v seznamu; ulici obchod mít nemusí. */
  protected addressLine(store: Store): string {
    return [store.street, this.cityLine(store)].filter(Boolean).join(' · ');
  }

  private selectionTouched = false;
  private searchTimer?: ReturnType<typeof setTimeout>;

  ngOnInit(): void {
    const fromParent = this.selectedStoreId();
    const id = fromParent ?? this.lastStore.read();
    if (!id) return;
    this.searching.set(true);
    this.storeService.getById(id).subscribe({
      next: (store) => {
        this.searching.set(false);
        if (this.selectionTouched) return;
        if (store) {
          this.suggestions.set([store]);
          this.onSelectId(store.id);
        } else if (fromParent) {
          // ID přišlo od rodiče a nic mu neodpovídá (skrytý/smazaný obchod) — zapamatovaná
          // volba je něco jiného a mazat se nesmí, jen se rodiči ohlásí prázdný výběr.
          this.onSelectId(null, false);
        } else {
          this.lastStore.clear();
        }
      },
      error: () => {
        this.searching.set(false);
        // Výpadek sítě nesmí smazat zapamatovaný obchod.
      },
    });
  }

  onSearch(query: string): void {
    if (query.trim()) this.selectionTouched = true;
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

  /** `forget` = smazat i zapamatovaný obchod; false znamená "jen ohlaš prázdný výběr rodiči". */
  onSelectId(id: string | null, forget = true): void {
    this.selectionTouched = true;
    this.picking.set(false);
    const store = this.displayOptions().find((s) => s.id === id) ?? null;
    this.selectedStore.set(store);
    if (store) this.locationError.set(null);
    if (store) this.lastStore.remember(store.id);
    else if (forget) this.lastStore.clear();
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
            // Nejbližší obchod se pořád předvybere (jeden klik = hotovo), ale u víc nálezů
            // zůstane seznam otevřený, aby šly zvolit i ty další.
            if (stores.length > 1) this.picking.set(true);
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
