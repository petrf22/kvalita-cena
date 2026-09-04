import { Component, WritableSignal, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
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
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzListModule } from 'ng-zorro-antd/list';
import { NzPaginationModule } from 'ng-zorro-antd/pagination';
import { NzPopconfirmModule } from 'ng-zorro-antd/popconfirm';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { Observable } from 'rxjs';
import { Viewer } from '../../models/auth';
import {
  FeedbackItem,
  FlaggedRecordItem,
  DuplicateCandidateItem,
  ModerationObservationItem,
  ProductSummary,
  RecordType,
} from '../../models/catalog';
import { AuthService } from '../../services/auth-service';
import { ModerationService } from '../../services/moderation-service';
import { ProductService } from '../../services/product-service';
import { ViewerService } from '../../services/viewer-service';
import {
  CLIENT_KIND_KEYS,
  FEEDBACK_CATEGORY_KEYS,
  PRICE_KIND_KEYS,
  RECORD_TYPE_KEYS,
} from '../../shared/enum-labels';
import { MoneyPipe } from '../../shared/money.pipe';
import { RelativeDatePipe } from '../../shared/relative-date.pipe';
import { translateError } from '../../shared/error-message';

const MERGE_SEARCH_DEBOUNCE_MS = 300;
const DEFAULT_PAGE_SIZE = 10;
const PAGE_SIZE_OPTIONS = [10, 20, 50, 100];

interface PagedResult<T> {
  items: T[];
  totalCount: number;
}

/** Stav jedné sekce (fronta nahlášení / ceny) se skutečným stránkováním, stejný vzor jako
 *  `features/my-contributions/my-contributions-page.ts`. */
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
 * Nástroj pro T4 (docs/reputace.md, "Odstupňování přístupu"; "Moderace") — fronta nahlášených
 * záznamů (core.record_flag), zamítání jednotlivých cen a pozastavování účtů
 * (docs/podminky-uziti.md, "Moderace a nahlašování"; "Ukončení a vyloučení"). Server
 * autorizaci vynucuje sám (`MODERATION_REQUIRES_ROLE`) — `Viewer.moderator` tady jen řídí
 * zobrazení, stejně jako odkaz v `features/login/login-page.html`. Jen web, mobil nemá
 * (nástroj provozovatele, ne appky).
 *
 * <p>Pozastavení účtu se ovládá přes `publicUid`, ne přes vykreslenou přezdívku ("Modrý čáp
 * #4271") — ta je jazykově vykreslená z kanonického klíče a nedá se spolehlivě rozebrat zpátky
 * (docs/soukromi.md). `authorPublicUid` je vidět u každého nahlášeného záznamu i ceny, moderátor
 * ho zkopíruje do záložky Účty.
 */
@Component({
  selector: 'app-moderation-page',
  imports: [
    FormsModule,
    RouterLink,
    TranslocoDirective,
    TranslocoPipe,
    NzAlertModule,
    NzButtonModule,
    NzEmptyModule,
    NzFormModule,
    NzInputModule,
    NzListModule,
    NzPaginationModule,
    NzPopconfirmModule,
    NzSelectModule,
    NzSpinModule,
    NzTabsModule,
    NzTagModule,
    MoneyPipe,
    RelativeDatePipe,
  ],
  providers: [provideTranslocoScope('moderation')],
  templateUrl: './moderation-page.html',
  styleUrl: './moderation-page.css',
})
export class ModerationPage {
  protected readonly auth = inject(AuthService);
  private readonly viewerService = inject(ViewerService);
  private readonly moderationService = inject(ModerationService);
  private readonly productService = inject(ProductService);
  private readonly transloco = inject(TranslocoService);

  protected readonly viewer = signal<Viewer | null>(null);
  protected readonly viewerLoading = signal(false);

  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  protected readonly priceKindKeys = PRICE_KIND_KEYS;
  protected readonly recordTypeKeys = RECORD_TYPE_KEYS;
  protected readonly recordTypeFilterOptions: (RecordType | null)[] = [
    null,
    RecordType.Product,
    RecordType.Store,
    RecordType.Photo,
  ];

  protected readonly recordTypeFilter = signal<RecordType | null>(null);
  protected readonly flagged = createSection<FlaggedRecordItem>((first, offset) =>
    this.moderationService.flaggedRecords(this.recordTypeFilter(), first, offset),
  );
  protected readonly flaggedActionLoading = signal<string | null>(null);
  protected readonly mergeTargetIds = signal<Record<string, string>>({});
  /** Kandidáti na cíl sloučení pro jeden zdrojový produkt — klíčem je jeho recordId. */
  protected readonly mergeCandidates = signal<Record<string, ProductSummary[]>>({});
  protected readonly mergeSearching = signal<string | null>(null);
  private mergeSearchTimer?: ReturnType<typeof setTimeout>;
  protected readonly mergeSuccess = signal<string | null>(null);

  /** Fronta podezřelých duplicit bezkódového zboží — nestojí na nahlášení, viz ModerationService. */
  protected readonly duplicates = createSection<DuplicateCandidateItem>((first, offset) =>
    this.moderationService.duplicateCandidates(first, offset),
  );
  protected readonly duplicateActionLoading = signal<string | null>(null);
  protected readonly renameTargets = signal<Record<string, string>>({});
  protected readonly renameLoading = signal<string | null>(null);

  protected readonly observationProductId = signal('');
  protected readonly observationStoreId = signal('');
  protected readonly observations = createSection<ModerationObservationItem>((first, offset) =>
    this.moderationService.moderationObservations(
      this.observationProductId().trim() || null,
      this.observationStoreId().trim() || null,
      first,
      offset,
    ),
  );
  protected readonly observationActionLoading = signal<string | null>(null);

  protected readonly suspendPublicUid = signal('');
  protected readonly suspendReason = signal('');
  protected readonly suspendLoading = signal(false);
  protected readonly suspendResult = signal<string | null>(null);
  protected readonly suspendError = signal<string | null>(null);

  protected readonly feedbackCategoryKeys = FEEDBACK_CATEGORY_KEYS;
  protected readonly clientKindKeys = CLIENT_KIND_KEYS;
  // false = jen nevyřízené (výchozí, ať fronta začne tím, co ještě čeká), null = obojí.
  protected readonly feedbackHandledFilter = signal<boolean | null>(false);
  protected readonly feedback = createSection<FeedbackItem>((first, offset) =>
    this.moderationService.feedbackItems(this.feedbackHandledFilter(), false, first, offset),
  );
  protected readonly feedbackActionLoading = signal<string | null>(null);

  // Samostatná fronta pro FeedbackSpamDetector (docs/nasazeni.md, "obrana proti spamu") — jde
  // vždy o VŠECHNY podezřelé zprávy bez ohledu na handled, ať se karanténa neschová za
  // výchozí filtr běžné fronty výš.
  protected readonly quarantinedFeedback = createSection<FeedbackItem>((first, offset) =>
    this.moderationService.feedbackItems(null, true, first, offset),
  );
  protected readonly quarantinedActionLoading = signal<string | null>(null);

  constructor() {
    if (this.auth.isLoggedIn()) {
      this.viewerLoading.set(true);
      this.viewerService.me().subscribe({
        next: (viewer) => {
          this.viewer.set(viewer);
          this.viewerLoading.set(false);
          if (viewer?.moderator) {
            this.load(this.flagged);
            this.load(this.duplicates);
            this.load(this.observations);
            this.load(this.feedback);
            this.load(this.quarantinedFeedback);
          }
        },
        error: () => this.viewerLoading.set(false),
      });
    }
  }

  protected onPageIndexChange<T>(section: Section<T>, pageIndex: number): void {
    section.pageIndex.set(pageIndex);
    this.load(section);
  }

  protected onPageSizeChange<T>(section: Section<T>, pageSize: number): void {
    section.pageSize.set(pageSize);
    section.pageIndex.set(1);
    this.load(section);
  }

  protected onRecordTypeFilterChange(): void {
    this.flagged.pageIndex.set(1);
    this.load(this.flagged);
  }

  protected onObservationFilterChange(): void {
    this.observations.pageIndex.set(1);
    this.load(this.observations);
  }

  protected resolveFlags(item: FlaggedRecordItem, resolution: 'DISMISSED' | 'UPHELD'): void {
    const key = item.recordType + item.recordId;
    this.flaggedActionLoading.set(key);
    this.moderationService.resolveFlags(item.recordType, item.recordId, resolution).subscribe({
      next: () => {
        this.flaggedActionLoading.set(null);
        this.load(this.flagged);
      },
      error: (err: unknown) => {
        this.flaggedActionLoading.set(null);
        this.flagged.error.set(translateError(err, this.transloco));
      },
    });
  }

  protected mergeTargetId(sourceId: string): string {
    return this.mergeTargetIds()[sourceId] ?? '';
  }

  protected setMergeTargetId(sourceId: string, targetId: string): void {
    this.mergeTargetIds.update((current) => ({ ...current, [sourceId]: targetId }));
  }

  protected mergeCandidatesFor(sourceId: string): ProductSummary[] {
    return this.mergeCandidates()[sourceId] ?? [];
  }

  /**
   * Kandidáti na cíl sloučení — `productSuggestions` v rozsahu zdrojové položky, ať moderátor
   * neopisuje číselné ID z jiné obrazovky. Prázdný dotaz vrátí celou lokální nabídku
   * provozovny, takže se seznam nabídne hned po otevření. U položky s rozsahem CHAIN žádné
   * `storeId` nemáme (scopeStore je NULL), takže se hledá napříč rozsahy a nesmyslnou dvojici
   * odmítne až server (MODERATION_PRODUCT_MERGE_SCOPE_MISMATCH).
   */
  protected onMergeSearch(item: FlaggedRecordItem, query: string): void {
    const scopeStoreId = item.product?.scopeStore?.id ?? null;
    clearTimeout(this.mergeSearchTimer);
    this.mergeSearchTimer = setTimeout(() => {
      this.mergeSearching.set(item.recordId);
      this.productService.suggestions(query.trim(), scopeStoreId, 10).subscribe({
        next: (result) => {
          this.mergeCandidates.update((current) => ({
            ...current,
            [item.recordId]: result.filter((candidate) => candidate.id !== item.recordId),
          }));
          this.mergeSearching.set(null);
        },
        error: () => this.mergeSearching.set(null),
      });
    }, MERGE_SEARCH_DEBOUNCE_MS);
  }

  protected onMergeOpenChange(item: FlaggedRecordItem, open: boolean): void {
    if (open && !this.mergeCandidates()[item.recordId]) this.onMergeSearch(item, '');
  }

  protected mergeProduct(item: FlaggedRecordItem): void {
    const targetId = this.mergeTargetId(item.recordId).trim();
    if (!targetId) return;
    const key = item.recordType + item.recordId;
    this.flaggedActionLoading.set(key);
    this.mergeSuccess.set(null);
    this.moderationService.mergeProducts(item.recordId, targetId).subscribe({
      next: (target) => {
        this.flaggedActionLoading.set(null);
        this.mergeSuccess.set(
          this.transloco.translate('moderation.flagged.mergeSuccess', { name: target.name }),
        );
        this.load(this.flagged);
      },
      error: (err: unknown) => {
        this.flaggedActionLoading.set(null);
        this.flagged.error.set(translateError(err, this.transloco));
      },
    });
  }

  protected renameTarget(productId: string): string {
    return this.renameTargets()[productId] ?? '';
  }

  protected setRenameTarget(productId: string, name: string): void {
    this.renameTargets.update((current) => ({ ...current, [productId]: name }));
  }

  /**
   * Oprava kanonického názvu bezkódové položky. `updateProduct` na to nestačí — ta ukládá
   * název jen jako osobní patch (core.product_user_edit), takže by překlep zmizel jen tomu,
   * kdo ho opravil, a ostatní by vedle něj dál zakládali duplicity.
   */
  protected renameProduct(product: ProductSummary): void {
    const name = this.renameTarget(product.id).trim();
    if (!name) return;
    this.renameLoading.set(product.id);
    this.moderationService.renameGenericProduct(product.id, name).subscribe({
      next: (renamed) => {
        this.renameLoading.set(null);
        this.setRenameTarget(product.id, '');
        this.mergeSuccess.set(
          this.transloco.translate('moderation.duplicates.renameSuccess', { name: renamed.name }),
        );
        this.load(this.duplicates);
        this.load(this.flagged);
      },
      error: (err: unknown) => {
        this.renameLoading.set(null);
        this.duplicates.error.set(translateError(err, this.transloco));
      },
    });
  }

  /** Moderátor volí směr sám — appka nemá jak vědět, který z dvojice je "ten správný". */
  protected mergeDuplicate(sourceId: string, targetId: string): void {
    this.duplicateActionLoading.set(sourceId + targetId);
    this.mergeSuccess.set(null);
    this.moderationService.mergeProducts(sourceId, targetId).subscribe({
      next: (target) => {
        this.duplicateActionLoading.set(null);
        this.mergeSuccess.set(
          this.transloco.translate('moderation.flagged.mergeSuccess', { name: target.name }),
        );
        this.load(this.duplicates);
      },
      error: (err: unknown) => {
        this.duplicateActionLoading.set(null);
        this.duplicates.error.set(translateError(err, this.transloco));
      },
    });
  }

  protected setObservationRejected(item: ModerationObservationItem, rejected: boolean): void {
    const id = item.observation.id;
    this.observationActionLoading.set(id);
    this.moderationService.setObservationRejected(id, rejected, null).subscribe({
      next: () => {
        this.observationActionLoading.set(null);
        this.load(this.observations);
      },
      error: (err: unknown) => {
        this.observationActionLoading.set(null);
        this.observations.error.set(translateError(err, this.transloco));
      },
    });
  }

  protected onFeedbackFilterChange(): void {
    this.feedback.pageIndex.set(1);
    this.load(this.feedback);
  }

  protected setFeedbackHandled(item: FeedbackItem, handled: boolean): void {
    this.feedbackActionLoading.set(item.id);
    this.moderationService.setFeedbackHandled(item.id, handled, null).subscribe({
      next: () => {
        this.feedbackActionLoading.set(null);
        this.load(this.feedback);
      },
      error: (err: unknown) => {
        this.feedbackActionLoading.set(null);
        this.feedback.error.set(translateError(err, this.transloco));
      },
    });
  }

  /** Cesta zpět z karantény (falešný poplach) — stejný princip jako resolveFlags DISMISSED. */
  protected setFeedbackQuarantined(item: FeedbackItem, quarantined: boolean): void {
    this.quarantinedActionLoading.set(item.id);
    this.moderationService.setFeedbackQuarantined(item.id, quarantined).subscribe({
      next: () => {
        this.quarantinedActionLoading.set(null);
        this.load(this.quarantinedFeedback);
        this.load(this.feedback);
      },
      error: (err: unknown) => {
        this.quarantinedActionLoading.set(null);
        this.quarantinedFeedback.error.set(translateError(err, this.transloco));
      },
    });
  }

  protected setUserSuspended(suspended: boolean): void {
    const publicUid = this.suspendPublicUid().trim();
    if (!publicUid) return;
    this.suspendLoading.set(true);
    this.suspendResult.set(null);
    this.suspendError.set(null);
    this.moderationService
      .setUserSuspended(publicUid, suspended, this.suspendReason().trim() || null)
      .subscribe({
        next: () => {
          this.suspendLoading.set(false);
          this.suspendResult.set(
            this.transloco.translate(
              suspended ? 'moderation.accounts.suspendedOk' : 'moderation.accounts.restoredOk',
            ),
          );
        },
        error: (err: unknown) => {
          this.suspendLoading.set(false);
          this.suspendError.set(translateError(err, this.transloco));
        },
      });
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
      error: (err: unknown) => {
        section.error.set(translateError(err, this.transloco));
        section.loading.set(false);
      },
    });
  }
}
