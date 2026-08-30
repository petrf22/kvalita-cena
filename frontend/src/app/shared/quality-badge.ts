import { Component, computed, inject, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoService } from '@jsverse/transloco';
import { NzRateModule } from 'ng-zorro-antd/rate';

/**
 * Hodnocení kvality hvězdičkami — 5 nejlepší (dřív školní známka 1–5, otočeno po testování,
 * viz docs/reputace.md). Pod MIN_RATINGS_FOR_BADGE hodnoceními se ukazuje jako "orientační",
 * obdoba pravidla n_eff < 2 u cen (docs/reputace.md) — práh drží i backend v
 * app.quality.min-ratings-for-badge. Mobilní protějšek: mobile ui/common/QualityBadge.kt.
 *
 * Malá samostatná komponenta bez vlastní stránky — používá přímo TranslocoService a kořenové
 * klíče (`quality.*`), místo aby si tahala vlastní scope (docs/lokalizace.md, stejný vzor jako
 * shared/relative-date.pipe.ts).
 */
const MIN_RATINGS_FOR_BADGE = 3;

@Component({
  selector: 'app-quality-badge',
  imports: [FormsModule, NzRateModule],
  template: `
    <span class="quality-badge" [attr.aria-label]="text()">
      <nz-rate
        [ngModel]="starsValue()"
        name="qualityBadgeStars"
        nzDisabled
        nzAllowHalf
        aria-hidden="true"
      />
      <span class="quality-badge-text" [class.quality-badge-empty]="average() == null">{{
        text()
      }}</span>
    </span>
  `,
  styles: `
    .quality-badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    /* nz-rate nemá vstup na velikost hvězd — v tabulkovém řádku je výchozích 20px moc velké. */
    ::ng-deep .quality-badge .ant-rate {
      font-size: 14px;
    }
    .quality-badge-text {
      font-size: 12px;
      color: rgba(0, 0, 0, 0.65);
    }
    .quality-badge-empty {
      color: rgba(0, 0, 0, 0.45);
    }
  `,
})
export class QualityBadge {
  private readonly transloco = inject(TranslocoService);

  readonly average = input<number | null>(null);
  readonly count = input<number>(0);

  /** Zaokrouhleno na půlku hvězdy — nz-rate s nzAllowHalf jinak bere JAKOUKOLI necelou
   *  hodnotu jako .5 (hasHalf = !Number.isInteger(input)), takže by 4,3 i 4,9 vykreslilo
   *  stejně jako 4,5. */
  protected readonly starsValue = computed(() => {
    const average = this.average();
    return average == null ? 0 : Math.round(average * 2) / 2;
  });

  // Metoda, ne computed() — translate() není signálově reaktivní, na změnu jazyka reaguje
  // appka přes reRenderOnLangChange (app.config.ts), stejně jako price-chart.ts.
  protected text(): string {
    const average = this.average();
    if (average == null) return this.transloco.translate('quality.notRatedYet');
    const count = this.count();
    const key =
      count > 0 && count < MIN_RATINGS_FOR_BADGE ? 'quality.ratedApproximate' : 'quality.rated';
    return this.transloco.translate(key, { value: average.toFixed(1) });
  }
}
