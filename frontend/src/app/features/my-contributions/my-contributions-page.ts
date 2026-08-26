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
import { NzPaginationModule } from 'ng-zorro-antd/pagination';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { Observable } from 'rxjs';
import { Viewer } from '../../models/auth';
import { MyEditItem, MyObservationItem, MyProductItem, MyStoreItem } from '../../models/catalog';
import { AuthService } from '../../services/auth-service';
import { FormatService } from '../../services/format-service';
import { MyContributionsService } from '../../services/my-contributions-service';
import { ViewerService } from '../../services/viewer-service';
import { PRICE_KIND_KEYS, RECORD_TYPE_KEYS } from '../../shared/enum-labels';
import { MoneyPipe } from '../../shared/money.pipe';
import { PublicationStatusBadge } from '../../shared/publication-status';
import { RelativeDatePipe } from '../../shared/relative-date.pipe';

const DEFAULT_PAGE_SIZE = 10;
const PAGE_SIZE_OPTIONS = [10, 20, 50, 100];

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
  url: 'my-contributions.field.url',
};

interface PagedResult<T> {
  items: T[];
  totalCount: number;
}

/**
 * Stav jedné sekce výpisu (zboží/obchody/ceny/úpravy) — čtyři instance, jedna na záložku.
 * Skutečné stránkování (ne "načíst další"), aby si při hodně položkách appka nikdy netáhla
 * celý seznam — velikost stránky je uživatelova volba (`nz-pagination`, `nzShowSizeChanger`).
 */
interface Section<T> {
  items: WritableSignal<T[]>;
  totalCount: WritableSignal<number>;
  pageIndex: WritableSignal<number>;
  pageSize: WritableSignal<number>;
  loading: WritableSignal<boolean>;
  error: WritableSignal<string | null>;
  fetch: (first: number, offset: number) => Observable<PagedResult<T>>;
}

function createSection<T>(fetch: Section<T>['fetch']): Section<T> {
  return {
    items: signal<T[]>([]),
    totalCount: signal(0),
    pageIndex: signal(1),
    pageSize: signal(DEFAULT_PAGE_SIZE),
    loading: signal(false),
    error: signal<string | null>(null),
    fetch,
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
    NzPaginationModule,
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
  protected readonly format = inject(FormatService);

  protected readonly viewer = signal<Viewer | null>(null);

  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;

  protected readonly products = createSection<MyProductItem>((first, offset) =>
    this.myContributionsService.myProducts(first, offset),
  );
  protected readonly stores = createSection<MyStoreItem>((first, offset) =>
    this.myContributionsService.myStores(first, offset),
  );
  protected readonly observations = createSection<MyObservationItem>((first, offset) =>
    this.myContributionsService.myObservations(first, offset),
  );
  protected readonly edits = createSection<MyEditItem>((first, offset) =>
    this.myContributionsService.myEdits(first, offset),
  );

  protected readonly priceKindKeys = PRICE_KIND_KEYS;
  protected readonly recordTypeKeys = RECORD_TYPE_KEYS;
  protected readonly changedFieldKeys = CHANGED_FIELD_KEYS;

  constructor() {
    if (this.auth.isLoggedIn()) {
      this.viewerService.me().subscribe((viewer) => this.viewer.set(viewer));
      this.load(this.products);
      this.load(this.stores);
      this.load(this.observations);
      this.load(this.edits);
    }
  }

  /** Popisek pole pro `changedFields` — neznámé (budoucí) pole se ukáže surové, ne rozbité. */
  protected fieldLabel(field: string): string {
    const key = this.changedFieldKeys[field];
    return key ? this.transloco.translate(key) : field;
  }

  protected onPageIndexChange<T>(section: Section<T>, pageIndex: number): void {
    section.pageIndex.set(pageIndex);
    this.load(section);
  }

  /** Změna velikosti stránky (uživatelova volba) vždy skočí zpátky na první stránku. */
  protected onPageSizeChange<T>(section: Section<T>, pageSize: number): void {
    section.pageSize.set(pageSize);
    section.pageIndex.set(1);
    this.load(section);
  }

  private load<T>(section: Section<T>): void {
    const pageSize = section.pageSize();
    const offset = (section.pageIndex() - 1) * pageSize;
    section.loading.set(true);
    section.error.set(null);
    section.fetch(pageSize, offset).subscribe({
      next: (result) => {
        section.items.set(result.items);
        section.totalCount.set(result.totalCount);
        section.loading.set(false);
      },
      error: () => {
        section.error.set(this.transloco.translate('my-contributions.loadFailed'));
        section.loading.set(false);
      },
    });
  }
}
