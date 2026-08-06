import { PricePoint } from '../../models/catalog';

export interface ChartPoint {
  x: number;
  y: number;
}

export interface ChartGeometry {
  /** Vrcholy schodové (step-after) křivky — cena platí, dokud ji někdo nezmění, žádná diagonála. */
  stepPath: ChartPoint[];
  /** Body skutečných měření (dny, kdy někdo hlásil) — na ně se kreslí značky a hledá se k nim hover. */
  markers: ChartPoint[];
  minPrice: number;
  maxPrice: number;
}

const EMPTY_GEOMETRY: ChartGeometry = { stepPath: [], markers: [], minPrice: 0, maxPrice: 0 };
const MS_PER_DAY = 86_400_000;

/**
 * Čistá funkce bez Angular/DOM závislostí — testovatelná bez komponenty (viz
 * price-chart-geometry.spec.ts). Mobilní protějšek: mobile ui/detail/PriceChartGeometry.kt —
 * oba mají stejný algoritmus, ať grafy na obou platformách vypadají stejně.
 *
 * Osa X je skutečný čas (počet dní od prvního bodu), NE index bodu — v agg.price_daily jsou
 * jen dny, kdy někdo hlásil, indexová osa by z týdenní mezery udělala jeden krok a graf by lhal.
 */
export function computeGeometry(points: PricePoint[], width: number, height: number, padding: number): ChartGeometry {
  if (points.length === 0) return EMPTY_GEOMETRY;

  const days = points.map((p) => Date.parse(`${p.day}T00:00:00Z`));
  const prices = points.map((p) => p.unitPrice);

  const firstDay = days[0];
  const lastDay = days[days.length - 1];
  const totalDaySpan = Math.max(1, Math.round((lastDay - firstDay) / MS_PER_DAY));

  let minPrice = Math.min(...prices);
  let maxPrice = Math.max(...prices);
  if (minPrice === maxPrice) {
    // Jediný bod nebo všechny hodnoty stejné — rozsah uměle roztáhnout o ±10 %, ať se nedělí nulou.
    const pad = minPrice === 0 ? 1 : Math.abs(minPrice) * 0.1;
    minPrice -= pad;
    maxPrice += pad;
  }

  const plotWidth = Math.max(1, width - 2 * padding);
  const plotHeight = Math.max(1, height - 2 * padding);

  const xFor = (day: number): number => {
    if (points.length === 1) return padding + plotWidth / 2;
    const offset = Math.round((day - firstDay) / MS_PER_DAY);
    return padding + (offset / totalDaySpan) * plotWidth;
  };
  const yFor = (price: number): number => {
    const ratio = (price - minPrice) / (maxPrice - minPrice);
    return padding + (1 - ratio) * plotHeight;
  };

  const markers = days.map((day, index) => ({ x: xFor(day), y: yFor(prices[index]) }));

  // Schodová (step-after) křivka: z bodu i vodorovně až pod bod i+1, pak svisle na jeho úroveň.
  const stepPath: ChartPoint[] = [];
  markers.forEach((point, index) => {
    stepPath.push(point);
    if (index < markers.length - 1) {
      stepPath.push({ x: markers[index + 1].x, y: point.y });
    }
  });

  return { stepPath, markers, minPrice, maxPrice };
}
