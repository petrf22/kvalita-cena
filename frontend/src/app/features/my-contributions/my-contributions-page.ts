import { Component, WritableSignal, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  TranslocoDirective,
  TranslocoPipe,
  TranslocoService,
  provideTranslocoScope,
} from '@jsverse/transloco';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzEmptyModule } from 'ng-zorro-antd/empty';
import { NzListModule } from 'ng-zorro-antd/list';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { Observable } from 'rxjs';
import { Viewer } from '../../models/auth';
import { MyEditItem, MyObservationItem, MyProductItem, MyStoreItem } from '../../models/catalog';
import { AuthService } from '../../services/auth-service';
import { MyContributionsService } from '../../services/my-contributions-service';
import { ViewerService } from '../../services/viewer-service';
import { PRICE_KIND_KEYS, RECORD_TYPE_KEYS } from '../../shared/enum-labels';
import { MoneyPipe } from '../../shared/money.pipe';
import { PublicationStatusBadge } from '../../shared/publication-status';
import { RelativeDatePipe } from '../../shared/relative-date.pipe';

const PAGE_SIZE = 20;

/**
 * Strojové názvy polí, která `changedFields` (MyEditItem) posílá — backend úmyslně posílá
 * anglický název pole, ne přeložený popisek (stejný princip jako `extensions.code`,
 * docs/lokalizace.md). Neznámé pole (budoucí schéma napřed appky) zobrazí surový název, ať
 * stránka nespadne na chybějícím klíči.
 */
const CHANGED_FIELD_KEYS: Record<string, string> = {
  name: 'my-contributions.field.name',
  brand: 'my-contributions.field.brand',
  category: 'my-contributions.field.category',
  unitBase: 'my-contributions.field.unitBase',
  netContentValue: 'my-contributions.field.netContentValue',
  netContentUom: 'my-contributions.field.netContentUom',
  netContentBase: 'my-contributions.field.netContentBase',
  piecesInPack: 'my-contributions.field.piecesInPack',
  isVariableWeight: 'my-contributions.field.isVariableWeight',
  chain: 'my-contributions.field.chain',
  street: 'my-contributions.field.street',
  city: 'my-contributions.field.city',
  postalCode: 'my-contributions.field.postalCode',
  ico: 'my-contributions.field.ico',
  lat: 'my-contributions.field.lat',
  lon: 'my-contributions.field.lon',
  geoSource: 'my-contributions.field.geoSource',
  osmRef: 'my-contributions.field.osmRef',
};

interface PagedResult<T> {
  items: T[];
  totalCount: number;
  hasMore: boolean;
}

/** Stav jedné sekce výpisu (zboží/obchody/ceny/úpravy) — čtyři instance, jedna na záložku. */
interface Section<T> {
  items: WritableSignal<T[]>;
  totalCount: WritableSignal<number>;
  hasMore: WritableSignal<boolean>;
  loading: WritableSignal<boolean>;
  error: WritableSignal<string | null>;
}

function createSection<T>(): Section<T> {
  return {
    items: signal<T[]>([]),
    totalCount: signal(0),
    hasMore: signal(false),
    loading: signal(false),
    error: signal<string | null>(null),
  };
}

/**
 * "Moje příspěvky" (docs/datovy-model.md, "Uživatelská vrstva nad globálními daty"; prahy
 * v docs/reputace.md) — jádro appky ověřitelné end-to-end: co jsem zadal/a a KDY se to
 * propaguje ostatním, se skutečnými čísly, ne jen štítkem. Dostupné z Účtu (login-page.html),
 * mobilní protějšek: mobile ui/contributions/MyContributionsScreen.kt.
 */
@Component({
  selector: 'app-my-contributions-page',
  imports: [
    RouterLink,
    TranslocoDirective,
    TranslocoPipe,
    NzAlertModule,
    NzButtonModule,
    NzEmptyModule,
    NzListModule,
    NzSpinModule,
    NzTabsModule,
    NzTagModule,
    MoneyPipe,
    RelativeDatePipe,
    PublicationStatusBadge,
  ],
  providers: [provideTranslocoScope('my-contributions')],
  templateUrl: './my-contributions-page.html',
  styleUrl: './my-contributions-page.css',
})
export class MyContributionsPage {
  protected readonly auth = inject(AuthService);
  private readonly viewerService = inject(ViewerService);
  private readonly myContributionsService = inject(MyContributionsService);
  private readonly transloco = inject(TranslocoService);

  protected readonly viewer = signal<Viewer | null>(null);

  protected readonly products = createSection<MyProductItem>();
  protected readonly stores = createSection<MyStoreItem>();
  protected readonly observations = createSection<MyObservationItem>();
  protected readonly edits = createSection<MyEditItem>();

  protected readonly priceKindKeys = PRICE_KIND_KEYS;
  protected readonly recordTypeKeys = RECORD_TYPE_KEYS;
  protected readonly changedFieldKeys = CHANGED_FIELD_KEYS;

  constructor() {
    if (this.auth.isLoggedIn()) {
      this.viewerService.me().subscribe((viewer) => this.viewer.set(viewer));
      this.loadMore(
        this.products,
        (first, offset) => this.myContributionsService.myProducts(first, offset),
        true,
      );
      this.loadMore(
        this.stores,
        (first, offset) => this.myContributionsService.myStores(first, offset),
        true,
      );
      this.loadMore(
        this.observations,
        (first, offset) => this.myContributionsService.myObservations(first, offset),
        true,
      );
      this.loadMore(
        this.edits,
        (first, offset) => this.myContributionsService.myEdits(first, offset),
        true,
      );
    }
  }

  /** Popisek pole pro `changedFields` — neznámé (budoucí) pole se ukáže surové, ne rozbité. */
  protected fieldLabel(field: string): string {
    const key = this.changedFieldKeys[field];
    return key ? this.transloco.translate(key) : field;
  }

  protected loadMoreProducts(): void {
    this.loadMore(
      this.products,
      (first, offset) => this.myContributionsService.myProducts(first, offset),
      false,
    );
  }

  protected loadMoreStores(): void {
    this.loadMore(
      this.stores,
      (first, offset) => this.myContributionsService.myStores(first, offset),
      false,
    );
  }

  protected loadMoreObservations(): void {
    this.loadMore(
      this.observations,
      (first, offset) => this.myContributionsService.myObservations(first, offset),
      false,
    );
  }

  protected loadMoreEdits(): void {
    this.loadMore(
      this.edits,
      (first, offset) => this.myContributionsService.myEdits(first, offset),
      false,
    );
  }

  private loadMore<T>(
    section: Section<T>,
    fetch: (first: number, offset: number) => Observable<PagedResult<T>>,
    reset: boolean,
  ): void {
    const offset = reset ? 0 : section.items().length;
    section.loading.set(true);
    section.error.set(null);
    fetch(PAGE_SIZE, offset).subscribe({
      next: (result) => {
        section.items.update((current) => (reset ? result.items : [...current, ...result.items]));
        section.totalCount.set(result.totalCount);
        section.hasMore.set(result.hasMore);
        section.loading.set(false);
      },
      error: () => {
        section.error.set(this.transloco.translate('my-contributions.loadFailed'));
        section.loading.set(false);
      },
    });
  }
}
