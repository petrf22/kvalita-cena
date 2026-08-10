import { Component, inject, input } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

/**
 * Známka kvality jako ve škole — 1 nejlepší, 5 nejhorší (viz zadání). Pod
 * MIN_RATINGS_FOR_BADGE hodnoceními se ukazuje jako "orientační", obdoba pravidla
 * n_eff < 2 u cen (docs/reputace.md) — práh drží i backend v app.quality.min-ratings-for-badge.
 * Mobilní protějšek: mobile ui/common/QualityBadge.kt.
 *
 * Malá samostatná komponenta bez vlastní stránky — používá přímo TranslocoService a kořenové
 * klíče (`quality.*`), místo aby si tahala vlastní scope (docs/lokalizace.md, stejný vzor jako
 * shared/relative-date.pipe.ts).
 */
const MIN_RATINGS_FOR_BADGE = 3;

@Component({
  selector: 'app-quality-badge',
  template: `<span class="quality-badge" [class.quality-badge-empty]="average() == null">{{
    text()
  }}</span>`,
  styles: `
    .quality-badge {
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
