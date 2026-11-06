import { Component } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import type { Product } from '../models/catalog';
import { ProductCreatedVia } from '../models/generated/enums';

/**
 * „Zboží z účtenky" (`Product.createdVia = RECEIPT`) — druhová položka ze zveřejněného
 * nespárovaného řádku účtenky (docs/datovy-model.md, „Účtenky"). Název, cena, obchod a čas
 * nákupu jsou z účtenky; značka, gramáž ani čárový kód ověřené nejsou.
 */
export function isReceiptItem(product: Pick<Product, 'createdVia'> | null | undefined): boolean {
  return product?.createdVia === ProductCreatedVia.Receipt;
}

/**
 * Štítek „Z účtenky" všude, kde se zboží zobrazuje (hledání, detail, řádek účtenky, nabídka
 * zboží). Barva je stavová předvolba `nz-tag` (docs/design.md) — `warning` jako u odhadu
 * zboží na účtence: obojí znamená „než tomu uvěříš, podívej se". Okraje si řeší kontejner
 * (`gap`), proto má štítek nulový vlastní okraj.
 */
@Component({
  selector: 'app-receipt-item-tag',
  imports: [TranslocoDirective, NzTagModule, NzTooltipModule],
  template: `
    <ng-container *transloco="let t">
      <nz-tag nzColor="warning" nz-tooltip [nzTooltipTitle]="t('receiptItem.hint')">{{
        t('receiptItem.tag')
      }}</nz-tag>
    </ng-container>
  `,
  styles: `
    :host {
      display: inline-block;
    }

    nz-tag {
      margin: 0;
      font-weight: 400;
    }
  `,
})
export class ReceiptItemTag {}
