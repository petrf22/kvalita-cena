import { Component, computed, input, signal } from '@angular/core';
import { PricePoint } from '../../models/catalog';
import { ChartPoint, computeGeometry } from './price-chart-geometry';

const WIDTH = 640;
const HEIGHT = 240;
const PADDING = 24;
const GRID_LINES = 4;

/**
 * Graf vývoje ceny — vlastní inline SVG, žádná nová závislost (viz plán). Jedna řada v barvě
 * primary, schodová křivka, mřížka vlasová plná (ne čárkovaná), popisky nikdy v barvě dat.
 * Mobilní protějšek: mobile ui/detail/PriceChart.kt (stejný algoritmus geometrie).
 */
@Component({
  selector: 'app-price-chart',
  templateUrl: './price-chart.html',
  styleUrl: './price-chart.css',
})
export class PriceChart {
  readonly points = input.required<PricePoint[]>();

  protected readonly width = WIDTH;
  protected readonly height = HEIGHT;
  protected readonly padding = PADDING;

  protected readonly hoveredIndex = signal<number | null>(null);

  protected readonly geometry = computed(() => computeGeometry(this.points(), WIDTH, HEIGHT, PADDING));

  protected readonly gridTicks = computed(() => {
    const plotHeight = HEIGHT - 2 * PADDING;
    return Array.from({ length: GRID_LINES + 1 }, (_, i) => PADDING + (plotHeight * i) / GRID_LINES);
  });

  protected readonly pathD = computed(() => this.toPathD(this.geometry().stepPath));

  protected readonly areaD = computed(() => {
    const path = this.geometry().stepPath;
    if (path.length < 2) return '';
    const baseline = HEIGHT - PADDING;
    const first = path[0];
    const last = path[path.length - 1];
    return `M ${first.x},${baseline} ${path.map((p) => `L ${p.x},${p.y}`).join(' ')} L ${last.x},${baseline} Z`;
  });

  protected readonly lastPoint = computed<PricePoint | null>(() => {
    const points = this.points();
    return points.length > 0 ? points[points.length - 1] : null;
  });

  protected readonly minPoint = computed<PricePoint | null>(() => this.extremePoint((a, b) => a.unitPrice < b.unitPrice));
  protected readonly maxPoint = computed<PricePoint | null>(() => this.extremePoint((a, b) => a.unitPrice > b.unitPrice));

  protected readonly hoveredPoint = computed<PricePoint | null>(() => {
    const index = this.hoveredIndex();
    if (index == null) return null;
    return this.points()[index] ?? null;
  });

  protected readonly hoveredX = computed<number | null>(() => {
    const index = this.hoveredIndex();
    if (index == null) return null;
    return this.geometry().markers[index]?.x ?? null;
  });

  onPointerMove(event: PointerEvent): void {
    const markers = this.geometry().markers;
    if (markers.length === 0) return;

    // Overlay <rect> vyplňuje celý viewBox, takže jeho vlastní getBoundingClientRect je
    // stejná plocha jako celé SVG — netřeba samostatná reference na <svg>.
    const rect = (event.currentTarget as Element).getBoundingClientRect();
    if (rect.width === 0) return;
    const svgX = ((event.clientX - rect.left) / rect.width) * WIDTH;

    let nearest = 0;
    let nearestDistance = Infinity;
    markers.forEach((marker, index) => {
      const distance = Math.abs(marker.x - svgX);
      if (distance < nearestDistance) {
        nearestDistance = distance;
        nearest = index;
      }
    });
    this.hoveredIndex.set(nearest);
  }

  onPointerLeave(): void {
    this.hoveredIndex.set(null);
  }

  protected formatUnitPrice(point: PricePoint | null): string {
    return point ? `${point.unitPrice.toFixed(2)} Kč/jednotka` : '';
  }

  private toPathD(path: ChartPoint[]): string {
    if (path.length === 0) return '';
    return `M ${path[0].x},${path[0].y} ${path
      .slice(1)
      .map((p) => `L ${p.x},${p.y}`)
      .join(' ')}`;
  }

  private extremePoint(isBetter: (a: PricePoint, b: PricePoint) => boolean): PricePoint | null {
    const points = this.points();
    if (points.length === 0) return null;
    return points.reduce((best, current) => (isBetter(current, best) ? current : best), points[0]);
  }
}
