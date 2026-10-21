import { Component, input } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { NzCardModule } from 'ng-zorro-antd/card';
import { ProductStoreLabel } from '../../models/catalog';

/**
 * „Názvy na účtenkách" — jak zboží tisknou obchody na účtenku (`Product.storeLabels`,
 * core.product_store_label, docs/datovy-model.md). Katalogový údaj, ne údaj o nákupu: nikde
 * tu není, kdo a kdy zboží koupil (docs/soukromi.md, „Účtenky"). Bez označení se karta vůbec
 * neukáže — prázdná karta by u většiny zboží jen zabírala místo.
 *
 * Pozor na jméno: `shared/store-label.ts` je něco jiného (popisek obchodu „název — město").
 */
@Component({
  selector: 'app-receipt-labels',
  imports: [TranslocoDirective, NzCardModule],
  template: `
    @if (labels().length > 0) {
      <ng-container *transloco="let t; scope: 'product-detail'">
        <nz-card [nzTitle]="t('product-detail.receiptLabels.title')" class="receipt-labels-card">
          <p class="hint">{{ t('product-detail.receiptLabels.hint') }}</p>
          <ul class="labels">
            @for (item of labels(); track item.label + (item.chainName ?? item.storeName)) {
              <li class="label-row">
                <span class="label">{{ item.label }}</span>
                <span class="scope">{{
                  item.chainName ?? item.storeName ?? t('product-detail.receiptLabels.unknownScope')
                }}</span>
              </li>
            }
          </ul>
        </nz-card>
      </ng-container>
    }
  `,
  styles: `
    .receipt-labels-card {
      margin-bottom: var(--kc-space-5);
    }
    .hint {
      margin: 0 0 var(--kc-space-3);
      font-size: var(--kc-font-sm);
      color: var(--kc-text-secondary);
    }
    .labels {
      display: grid;
      gap: var(--kc-space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .label-row {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--kc-space-1) var(--kc-space-3);
    }
    .label {
      font-family: monospace;
      font-weight: 600;
      overflow-wrap: anywhere;
    }
    .scope {
      font-size: var(--kc-font-sm);
      color: var(--kc-text-muted);
    }
  `,
})
export class ReceiptLabels {
  readonly labels = input.required<readonly ProductStoreLabel[]>();
}
