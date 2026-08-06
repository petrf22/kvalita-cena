import { Component, input } from '@angular/core';

/**
 * Známka kvality jako ve škole — 1 nejlepší, 5 nejhorší (viz zadání). Pod
 * MIN_RATINGS_FOR_BADGE hodnoceními se ukazuje jako "orientační", obdoba pravidla
 * n_eff < 2 u cen (docs/reputace.md) — práh drží i backend v app.quality.min-ratings-for-badge.
 * Mobilní protějšek: mobile ui/common/QualityBadge.kt.
 */
const MIN_RATINGS_FOR_BADGE = 3;

@Component({
  selector: 'app-quality-badge',
  template: `<span class="quality-badge" [class.quality-badge-empty]="average() == null">{{ text() }}</span>`,
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
  readonly average = input<number | null>(null);
  readonly count = input<number>(0);

  protected text(): string {
    const average = this.average();
    if (average == null) return 'Kvalita: zatím nehodnoceno';
    const base = `Kvalita ${average.toFixed(1)}/5`;
    const count = this.count();
    return count > 0 && count < MIN_RATINGS_FOR_BADGE ? `${base} (orientační)` : base;
  }
}
