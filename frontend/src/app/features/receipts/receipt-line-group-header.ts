import { Component, input, output } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { NzIconModule } from 'ng-zorro-antd/icon';
import type { LineGroup } from './receipt-status';

/**
 * Nadpis skupiny řádků účtenky s počtem (`receipt-status.ts`, `groupedLines`). Hotové
 * a ignorované řádky jdou sbalit — zajímá hlavně to, co ještě potřebuje člověka.
 */
@Component({
  selector: 'app-receipt-line-group-header',
  imports: [TranslocoDirective, NzIconModule],
  template: `
    <ng-container *transloco="let t">
      <h3 class="group">
        @if (collapsible()) {
          <button
            type="button"
            class="toggle"
            [attr.aria-expanded]="expanded()"
            (click)="toggled.emit()"
          >
            <span nz-icon [nzType]="expanded() ? 'down' : 'right'"></span>
            {{ t('receipts.group.' + group(), { count: count() }) }}
          </button>
        } @else {
          {{ t('receipts.group.' + group(), { count: count() }) }}
        }
      </h3>
    </ng-container>
  `,
  styles: `
    .group {
      margin: var(--kc-space-4) 0 0;
      font-size: var(--kc-font-md);
      font-weight: 600;
    }

    .toggle {
      display: inline-flex;
      align-items: center;
      gap: var(--kc-space-2);
      padding: 0;
      border: 0;
      background: none;
      font: inherit;
      color: inherit;
      cursor: pointer;
    }
  `,
})
export class ReceiptLineGroupHeader {
  readonly group = input.required<LineGroup>();
  readonly count = input.required<number>();
  readonly collapsible = input(false);
  readonly expanded = input(true);
  readonly toggled = output<void>();
}
