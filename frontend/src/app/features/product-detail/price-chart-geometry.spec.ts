import { computeGeometry } from './price-chart-geometry';
import { PricePoint } from '../../models/catalog';

function point(day: string, unitPrice: number): PricePoint {
  return { day, unitPrice, priceAmount: unitPrice, nObs: 1, storeCount: 1 };
}

describe('computeGeometry', () => {
  it('produces empty geometry for empty input', () => {
    const geometry = computeGeometry([], 300, 200, 24);
    expect(geometry.markers).toEqual([]);
    expect(geometry.stepPath).toEqual([]);
  });

  it('centers a single point and pads the range to avoid division by zero', () => {
    const geometry = computeGeometry([point('2026-07-01', 42)], 300, 200, 24);
    expect(geometry.markers).toHaveLength(1);
    expect(geometry.markers[0].x).toBeCloseTo(150, 1);
    expect(geometry.minPrice).toBeLessThan(geometry.maxPrice);
  });

  it('does not divide by zero when all values are equal', () => {
    const points = [point('2026-07-01', 10), point('2026-07-05', 10), point('2026-07-10', 10)];
    const geometry = computeGeometry(points, 300, 200, 24);
    expect(geometry.minPrice).toBeLessThan(geometry.maxPrice);
    // Se stejnou cenou musí být všechny body na stejné Y souřadnici (vodorovná čára).
    const ys = new Set(geometry.markers.map((m) => m.y));
    expect(ys.size).toBe(1);
  });

  it('reflects real time on the X axis, not point index', () => {
    // Mezera 1 den vs. mezera 8 dní — indexová osa by udělala z obojího stejný krok, tohle ne.
    const points = [point('2026-07-01', 10), point('2026-07-02', 12), point('2026-07-10', 14)];
    const geometry = computeGeometry(points, 300, 200, 0);
    const gapShort = geometry.markers[1].x - geometry.markers[0].x;
    const gapLong = geometry.markers[2].x - geometry.markers[1].x;
    expect(gapLong).toBeGreaterThan(gapShort * 4);
  });

  it('builds a step-after path (horizontal then vertical)', () => {
    const points = [point('2026-07-01', 10), point('2026-07-05', 20)];
    const geometry = computeGeometry(points, 300, 200, 0);
    expect(geometry.stepPath).toHaveLength(3); // bod0, mezikrok, bod1
    const step = geometry.stepPath[1];
    expect(step.x).toBeCloseTo(geometry.markers[1].x, 5);
    expect(step.y).toBeCloseTo(geometry.markers[0].y, 5);
  });
});
