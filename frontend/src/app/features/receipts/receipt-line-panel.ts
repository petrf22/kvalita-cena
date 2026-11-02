import { NgTemplateOutlet } from '@angular/common';
import { Component, inject, input, output } from '@angular/core';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { ProductSummary, ReceiptLine } from '../../models/catalog';
import { FormatService } from '../../services/format-service';
import { NET_CONTENT_UOM_KEYS } from '../../shared/enum-labels';
import { MoneyPipe } from '../../shared/money.pipe';
import { ProductThumb } from '../../shared/product-thumb';
import { ReceiptLineMatch } from './receipt-line-match';
import { isLineGuess, isLineOffSuggestion, lineGtin, lineRawLabel } from './receipt-status';

/**
 * Obsah panelu jednoho řádku účtenky (modál v detailu) — výchozí akce po kliknutí na řádek
 * a zároveň krok průvodce „Doplnit nerozpoznané". Ukazuje jen to, co řádek potřebuje:
 * - appka zboží nezná → hledání (`app-receipt-line-match`), založení nového a „Není zboží",
 * - návrh nebo odhad → navržené zboží s „Ano, je to ono" a „Jiné zboží",
 * - návrh z Open Food Facts → náhled s povinnou atribucí; zboží vznikne až potvrzením,
 * - ignorovaný řádek → vrácení mezi nespárované.
 *
 * Komponenta jen kreslí a hlásí volby; akce nad serverem dělá detail účtenky. Zrcadlo
 * mobilního `ReceiptLineSheet`.
 */
@Component({
  selector: 'app-receipt-line-panel',
  imports: [
    NgTemplateOutlet,
    TranslocoDirective,
    NzButtonModule,
    NzIconModule,
    MoneyPipe,
    ProductThumb,
    ReceiptLineMatch,
  ],
  templateUrl: './receipt-line-panel.html',
  styleUrl: './receipt-line-panel.css',
})
export class ReceiptLinePanel {
  private readonly format = inject(FormatService);
  private readonly transloco = inject(TranslocoService);

  readonly line = input.required<ReceiptLine>();
  readonly currency = input<string | null | undefined>(null);
  readonly quantityText = input('');
  /** Hledání místo navrženého zboží — u nespárovaného hned, u návrhu po „Jiné zboží". */
  readonly searching = input(false);
  readonly guideActive = input(false);
  readonly busy = input(false);
  readonly error = input<string | null>(null);
  readonly canCreate = input(true);

  readonly accepted = output<void>();
  readonly otherRequested = output<void>();
  readonly ignored = output<void>();
  readonly restored = output<void>();
  readonly picked = output<ProductSummary>();
  readonly createNew = output<void>();
  readonly skipped = output<void>();
  readonly closed = output<void>();

  protected readonly isGuess = isLineGuess;
  protected readonly isOffSuggestion = isLineOffSuggestion;
  protected readonly lineGtin = lineGtin;
  protected readonly lineRawLabel = lineRawLabel;

  protected offLabel(candidate: OffCandidate): string {
    return offCandidateLabel(candidate, this.format, this.transloco);
  }
}

type OffCandidate = NonNullable<ReceiptLine['offCandidate']>;

/** Značka · název · gramáž z Open Food Facts u návrhu `OFF` (zboží zatím neexistuje). */
export function offCandidateLabel(
  candidate: OffCandidate,
  format: FormatService,
  transloco: TranslocoService,
): string {
  const netContent =
    candidate.netContentValue != null && candidate.netContentUom
      ? `${format.number(candidate.netContentValue)} ${transloco.translate(
          NET_CONTENT_UOM_KEYS[candidate.netContentUom],
        )}`
      : null;
  return [candidate.brandName, candidate.name, netContent].filter(Boolean).join(' · ');
}
