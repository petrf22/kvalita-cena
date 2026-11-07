import {
  Component,
  EventEmitter,
  OnInit,
  Output,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { TranslocoDirective, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { OsmStoreCandidate, Store } from '../models/catalog';
import { OsmLookupStatus } from '../models/generated/enums';
import { AuthService } from '../services/auth-service';
import { ConnectivityService, isNetworkError } from '../services/connectivity-service';
import { CountryService } from '../services/country-service';
import { StoreService } from '../services/store-service';
import { LastStoreService } from '../services/last-store-service';
import { translateError } from './error-message';
import { osmCandidateAddress } from './osm-store-candidate';
import { StoreForm, StorePrefill } from './store-form';
import { storeLabel } from './store-label';

const SEARCH_DEBOUNCE_MS = 300;
/** Stejné vrátkování jako mobilní StorePicker (VISIBLE_NEARBY_COUNT) — zbytek na vyžádání. */
const VISIBLE_OSM_COUNT = 5;

/** Adresa a název obchodu, kolem kterých nabídnout provozovny z OpenStreetMap. */
export type OsmLookupAddress = Pick<StorePrefill, 'name' | 'street' | 'postalCode' | 'city'>;

/**
 * Stav nabídky z OSM — čtyři stavy z docs/design.md. `unavailable` (OSM je mimo) se záměrně
 * liší od prázdného `loaded`: kdyby se výpadek tvářil jako „nic tu není", uživatel by založil
 * duplicitu obchodu, který v OSM dávno je (stejný vzor jako ProductLookupStatus.OFF_UNAVAILABLE).
 */
type OsmLookupState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'loaded'; candidates: OsmStoreCandidate[]; attribution: string }
  | { kind: 'unavailable' }
  | { kind: 'error'; message: string }
  | { kind: 'offline' };

/**
 * Výběr obchodu při zápisu ceny — kombinuje tři cesty, jak se k obchodu dostat, aby fungoval
 * i doma bez sdílené polohy nebo zpětného zápisu (docs/datovy-model.md, "Identita provozovny"):
 * napsat název/město (searchStores), stisknout "Najít v okolí" (nearbyStores, dosavadní
 * chování z product-detail-page) nebo založit nový obchod v modalu. U účtenky (`osmAddress`)
 * navíc nabídne provozovny z OpenStreetMap kolem adresy z hlavičky — výběr otevře formulář
 * nového obchodu předvyplněný z OSM. Mobilní protějšek: mobile ui/common/StorePicker.kt.
 */
@Component({
  selector: 'app-store-picker',
  imports: [
    FormsModule,
    NzSelectModule,
    NzButtonModule,
    NzIconModule,
    NzModalModule,
    NzSpinModule,
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
  private readonly connectivity = inject(ConnectivityService);
  protected readonly auth = inject(AuthService);
  protected readonly countryService = inject(CountryService);
  protected readonly storeLabel = storeLabel;

  readonly selectedStoreId = input<string | null>(null);
  /** Čím předvyplnit formulář nového obchodu (detail účtenky), jinak se otevře prázdný. */
  readonly prefill = input<StorePrefill | null>(null);
  /**
   * Bez výběru od rodiče obnovit naposledy použitý obchod. U zápisu ceny je to zkratka (člověk
   * zapisuje v tomtéž obchodě), u účtenky past: účtenka je odjinud a předvybraný obchod by
   * stačilo omylem uložit (`features/receipts`).
   */
  readonly restoreLast = input(true);
  /**
   * Adresa obchodu z hlavičky účtenky — s městem picker jednou sám nabídne provozovny
   * z OpenStreetMap kolem ní (`osmStoresAtAddress`). Účtenka se zpracovává doma, takže hledání
   * podle polohy uživatele tu nedává smysl. Bez vstupu se nabídka neukazuje vůbec.
   */
  readonly osmAddress = input<OsmLookupAddress | null>(null);
  @Output() readonly selectedStoreIdChange = new EventEmitter<string | null>();
  /** Celý objekt vedle ID — price-entry-page ho potřebuje kvůli store.country (měna zápisu). */
  @Output() readonly selectedStoreChange = new EventEmitter<Store | null>();

  protected readonly suggestions = signal<Store[]>([]);
  protected readonly selectedStore = signal<Store | null>(null);
  protected readonly searching = signal(false);
  protected readonly locating = signal(false);
  protected readonly locationError = signal<string | null>(null);
  protected readonly showAddModal = signal(false);
  /** Kandidát z OSM, kterým se předvyplní formulář v modalu; `null` = „+ Nový obchod". */
  protected readonly osmPick = signal<OsmStoreCandidate | null>(null);

  protected readonly osm = signal<OsmLookupState>({ kind: 'idle' });
  private readonly osmShowAll = signal(false);
  protected readonly osmCandidateAddress = osmCandidateAddress;
  protected readonly osmCandidates = computed(() => {
    const state = this.osm();
    return state.kind === 'loaded' ? state.candidates : [];
  });
  protected readonly osmVisible = computed(() =>
    this.osmShowAll() ? this.osmCandidates() : this.osmCandidates().slice(0, VISIBLE_OSM_COUNT),
  );
  protected readonly osmHiddenCount = computed(
    () => this.osmCandidates().length - this.osmVisible().length,
  );
  protected readonly osmAttribution = computed(() => {
    const state = this.osm();
    return state.kind === 'loaded' ? state.attribution : null;
  });
  protected readonly osmError = computed(() => {
    const state = this.osm();
    return state.kind === 'error' ? state.message : null;
  });
  /** Klíč adresy, pro kterou už dotaz proběhl — účtenka se přenačítá a s ní i objekt adresy. */
  private osmQueryKey: string | null = null;
  private osmRequest = 0;
  /**
   * Rozepsaný výběr — karta s vybraným obchodem se schová a vrátí se seznam. Bez toho by po
   * "Najít v okolí" (vybere první nález) nešlo na zbylé obchody v okolí vůbec dosáhnout jinak
   * než vymazáním volby. Mobilní protějšek je expandSignal v SearchableDropdown.kt.
   */
  protected readonly picking = signal(false);

  /** Karta s vybraným obchodem místo seznamu — nabídka z OSM je pak jen šum, schová se. */
  protected readonly showSelectedCard = computed(() => {
    const store = this.selectedStore();
    return store != null && this.selectedStoreId() === store.id && !this.picking();
  });

  protected readonly osmSectionVisible = computed(
    () => this.osm().kind !== 'idle' && !this.showSelectedCard(),
  );

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

  constructor() {
    // Jednou na adresu: dotaz jde přes Nominatim a Overpass, přenačtení účtenky (polling, uložení
    // řádku) ho opakovat nesmí. Anonymovi se nenabízí — dotaz i createStore vyžadují přihlášení.
    effect(() => {
      const address = this.osmAddress();
      const city = address?.city?.trim();
      if (!address || !city || !this.auth.isLoggedIn()) return;
      const key = [address.name, address.street, address.postalCode, city]
        .map((part) => part?.trim() ?? '')
        .join('|');
      if (key === this.osmQueryKey) return;
      this.osmQueryKey = key;
      untracked(() => this.loadOsm());
    });
    // Offline je čekání, ne chyba — po připojení se nabídka dotáhne sama.
    this.connectivity.reconnected$.pipe(takeUntilDestroyed()).subscribe(() => {
      if (this.osm().kind === 'offline') this.loadOsm();
    });
  }

  ngOnInit(): void {
    const fromParent = this.selectedStoreId();
    const id = fromParent ?? (this.restoreLast() ? this.lastStore.read() : null);
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

  /** Opakování po výpadku OSM, chybě nebo návratu připojení — pro tutéž adresu. */
  loadOsm(): void {
    const address = this.osmAddress();
    const city = address?.city?.trim();
    if (!address || !city) return;
    const request = ++this.osmRequest;
    this.osmShowAll.set(false);
    if (!this.connectivity.online()) {
      this.osm.set({ kind: 'offline' });
      return;
    }
    this.osm.set({ kind: 'loading' });
    this.storeService
      .osmAtAddress(
        address.name?.trim() || null,
        address.street?.trim() || null,
        city,
        address.postalCode?.trim() || null,
      )
      .subscribe({
        next: (result) => {
          if (request !== this.osmRequest) return;
          this.osm.set(
            result.status === OsmLookupStatus.Unavailable
              ? { kind: 'unavailable' }
              : { kind: 'loaded', candidates: result.candidates, attribution: result.attribution },
          );
        },
        error: (err: unknown) => {
          if (request !== this.osmRequest) return;
          this.osm.set(
            isNetworkError(err)
              ? { kind: 'offline' }
              : { kind: 'error', message: translateError(err, this.transloco) },
          );
        },
      });
  }

  showAllOsm(): void {
    this.osmShowAll.set(true);
  }

  openNewStore(): void {
    this.osmPick.set(null);
    this.showAddModal.set(true);
  }

  /** Výběr z nabídky OSM neukládá nic — jen otevře formulář, kde uživatel údaje potvrdí. */
  openOsmCandidate(candidate: OsmStoreCandidate): void {
    this.osmPick.set(candidate);
    this.showAddModal.set(true);
  }

  onStoreCreated(store: Store): void {
    this.showAddModal.set(false);
    // Založená provozovna už v katalogu je — v nabídce by zůstala jako pozvánka k duplicitě.
    const picked = this.osmPick();
    if (picked) {
      this.osm.update((state) =>
        state.kind === 'loaded'
          ? { ...state, candidates: state.candidates.filter((c) => c.osmRef !== picked.osmRef) }
          : state,
      );
      this.osmPick.set(null);
    }
    this.suggestions.update((list) => [store, ...list]);
    this.onSelectId(store.id);
  }
}
