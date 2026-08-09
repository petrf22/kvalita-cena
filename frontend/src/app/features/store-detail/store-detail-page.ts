import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { Store } from '../../models/catalog';
import { AuthService } from '../../services/auth-service';
import { StoreService } from '../../services/store-service';
import { LocationMap } from '../../shared/location-map';
import { PhotoGallery } from '../../shared/photo-gallery';
import { StoreForm } from '../../shared/store-form';

/**
 * Detail provozovny — adresa, mapa, fotky, editace a nahlášení. Doplňuje CLAUDE.md, „inline
 * úprava obchodu v UI zatím chybí" — tahle stránka mutace `updateStore`/`flagRecord` konečně
 * volá. Mobilní protějšek: mobile ui/store/StoreDetailScreen.kt.
 */
@Component({
  selector: 'app-store-detail-page',
  imports: [
    RouterLink,
    NzCardModule,
    NzTagModule,
    NzButtonModule,
    NzIconModule,
    NzAlertModule,
    NzModalModule,
    LocationMap,
    PhotoGallery,
    StoreForm,
  ],
  templateUrl: './store-detail-page.html',
  styleUrl: './store-detail-page.css',
})
export class StoreDetailPage {
  private readonly route = inject(ActivatedRoute);
  private readonly storeService = inject(StoreService);
  protected readonly auth = inject(AuthService);

  protected readonly store = signal<Store | null>(null);
  protected readonly loading = signal(true);

  protected readonly editing = signal(false);

  protected readonly flagging = signal(false);
  protected readonly flagMessage = signal<string | null>(null);

  constructor() {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) this.loadStore(id);
  }

  private loadStore(id: string): void {
    this.loading.set(true);
    this.storeService.getById(id).subscribe({
      next: (store) => {
        this.store.set(store);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  /** null, když provozovna zatím nemá souřadnice (založená bez GPS, docs/datovy-model.md). */
  osmUrl(store: Store): string | null {
    if (store.lat == null || store.lon == null) return null;
    return `https://www.openstreetmap.org/?mlat=${store.lat}&mlon=${store.lon}#map=18/${store.lat}/${store.lon}`;
  }

  onStoreSaved(updated: Store): void {
    const current = this.store();
    // updateStore nevrací photos (STORE_FIELDS, ne STORE_DETAIL_FIELDS) — spread zachová
    // dosud načtenou galerii beze změny.
    this.store.set(current ? { ...current, ...updated } : updated);
    this.editing.set(false);
  }

  flagStore(): void {
    const store = this.store();
    if (!store) return;

    this.flagging.set(true);
    this.flagMessage.set(null);
    this.storeService.flag(store.id).subscribe({
      next: (result) => {
        this.flagging.set(false);
        this.flagMessage.set(
          result.hidden
            ? 'Díky za nahlášení — provozovna je teď skrytá a čeká na přezkum.'
            : 'Díky za nahlášení, zaznamenali jsme ho.',
        );
      },
      error: () => {
        this.flagging.set(false);
        this.flagMessage.set('Nahlášení se nepovedlo, zkus to prosím znovu.');
      },
    });
  }
}
