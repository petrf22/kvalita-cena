import { Component, inject, input } from '@angular/core';
import { TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { PublicationState, PublicationStatus } from '../models/catalog';
import { PublicationRecordKind, publicationStatusText } from './publication-status-text';

/**
 * Štítek + vysvětlující věta pro "Moje příspěvky" (docs/datovy-model.md, "Uživatelská vrstva
 * nad globálními daty") — sdílená mezi všemi čtyřmi sekcemi výpisu. Mapování stavu na klíč je
 * v `publication-status-text.ts` (čistá funkce, otestovaná zvlášť); tahle komponenta ji jen
 * zavolá a přeloží přes `TranslocoService` (metoda, ne `computed()` — stejný důvod jako
 * `shared/quality-badge.ts`: `translate()` není signálově reaktivní, na změnu jazyka appka
 * reaguje přes `reRenderOnLangChange`).
 */
@Component({
  selector: 'app-publication-status',
  imports: [NzTagModule],
  providers: [provideTranslocoScope('my-contributions')],
  template: `
    <nz-tag [nzColor]="tagColor()">{{ tagText() }}</nz-tag>
    <p class="publication-status-sentence">{{ sentence() }}</p>
  `,
  styles: `
    .publication-status-sentence {
      margin: 4px 0 0;
      color: rgba(0, 0, 0, 0.65);
      font-size: 13px;
    }
  `,
})
export class PublicationStatusBadge {
  private readonly transloco = inject(TranslocoService);

  readonly status = input.required<PublicationStatus>();
  readonly kind = input.required<PublicationRecordKind>();

  protected tagColor(): string {
    switch (this.status().state) {
      case PublicationState.Public:
        return 'green';
      case PublicationState.AwaitingConfirmations:
        return 'orange';
      case PublicationState.HiddenAfterFlags:
        return 'red';
      case PublicationState.PendingMerge:
        return 'blue';
      default:
        return 'default';
    }
  }

  protected tagText(): string {
    return this.transloco.translate(`my-contributions.state.${this.status().state}`);
  }

  protected sentence(): string {
    const { key, params } = publicationStatusText(this.status(), this.kind());
    return this.transloco.translate(key, params);
  }
}
