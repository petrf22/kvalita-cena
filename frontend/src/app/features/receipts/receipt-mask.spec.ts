import { describe, expect, it } from 'vitest';
import {
  DEFAULT_BAND_FRACTION,
  MIN_MASK_PX,
  ReceiptPart,
  defaultBand,
  isUsableMask,
  maskPixelRect,
  movePart,
  moveRect,
  rectFromPoints,
  rectToPercent,
  resizeRect,
  scaledSize,
  withDefaultBand,
} from './receipt-mask';

function part(id: number, overrides: Partial<ReceiptPart> = {}): ReceiptPart {
  return {
    id,
    name: `p${id}.jpg`,
    blob: new Blob(),
    url: `blob:${id}`,
    width: 1000,
    height: 2000,
    masks: [],
    masksTouched: false,
    ...overrides,
  };
}

describe('defaultBand', () => {
  it('zakryje spodní pás přes celou šířku', () => {
    const band = defaultBand(1000, 2000);
    expect(band).toEqual({
      x: 0,
      y: 2000 - 2000 * DEFAULT_BAND_FRACTION,
      w: 1000,
      h: 400,
      auto: true,
    });
  });

  it('u malinkého obrázku nespadne pod minimální velikost masky', () => {
    expect(defaultBand(100, 20).h).toBe(MIN_MASK_PX);
  });
});

describe('withDefaultBand', () => {
  it('dá výchozí pás jen poslední části — u ostatních by zakryl položky v překryvu', () => {
    const result = withDefaultBand([part(1), part(2), part(3)]);
    expect(result.map((p) => p.masks.length)).toEqual([0, 0, 1]);
    expect(result[2].masks[0].auto).toBe(true);
  });

  it('po přeřazení pás přestěhuje na novou poslední část', () => {
    const first = withDefaultBand([part(1), part(2)]);
    const moved = withDefaultBand(movePart(first, 2, -1));
    expect(moved.map((p) => p.id)).toEqual([2, 1]);
    expect(moved[0].masks).toEqual([]);
    expect(moved[1].masks).toHaveLength(1);
  });

  it('upravené masky části už nepřepisuje ani nedoplňuje pásem', () => {
    const own = { x: 10, y: 10, w: 50, h: 50 };
    const result = withDefaultBand([part(1, { masks: [own], masksTouched: true })]);
    expect(result[0].masks).toEqual([own]);
  });

  it('vlastní masky neposlední části zůstanou, zmizí jen automatický pás', () => {
    const own = { x: 10, y: 10, w: 50, h: 50 };
    const withBand = withDefaultBand([part(1, { masks: [own] })]);
    const result = withDefaultBand([...withBand, part(2)]);
    expect(result[0].masks).toEqual([own]);
    expect(result[1].masks[0].auto).toBe(true);
  });

  it('beze změny vrací stejné instance částí (žádné zbytečné překreslení)', () => {
    const once = withDefaultBand([part(1), part(2)]);
    const twice = withDefaultBand(once);
    expect(twice[0]).toBe(once[0]);
    expect(twice[1]).toBe(once[1]);
  });
});

describe('movePart', () => {
  it('mimo rozsah pořadí nemění', () => {
    const parts = [part(1), part(2)];
    expect(movePart(parts, 1, -1).map((p) => p.id)).toEqual([1, 2]);
    expect(movePart(parts, 2, 1).map((p) => p.id)).toEqual([1, 2]);
  });
});

describe('geometrie masky', () => {
  it('obdélník z tahu v libovolném směru, oříznutý do obrázku', () => {
    expect(rectFromPoints(300, 300, -50, 100, 1000, 2000)).toEqual({
      x: 0,
      y: 100,
      w: 300,
      h: 200,
    });
  });

  it('krátký tah není maska', () => {
    expect(isUsableMask({ x: 0, y: 0, w: MIN_MASK_PX - 1, h: 100 })).toBe(false);
    expect(isUsableMask({ x: 0, y: 0, w: MIN_MASK_PX, h: MIN_MASK_PX })).toBe(true);
  });

  it('posun drží masku celou uvnitř obrázku a zahodí příznak auto', () => {
    const moved = moveRect({ x: 900, y: 0, w: 200, h: 100, auto: true }, 500, -50, 1000, 2000);
    expect(moved).toEqual({ x: 800, y: 0, w: 200, h: 100 });
  });

  it('změna velikosti za roh drží protější roh a minimální velikost', () => {
    const rect = { x: 100, y: 100, w: 200, h: 200 };
    expect(resizeRect(rect, 'se', 50, 20, 1000, 2000)).toEqual({ x: 100, y: 100, w: 250, h: 220 });
    expect(resizeRect(rect, 'nw', 500, 500, 1000, 2000)).toEqual({
      x: 300 - MIN_MASK_PX,
      y: 300 - MIN_MASK_PX,
      w: MIN_MASK_PX,
      h: MIN_MASK_PX,
    });
  });

  it('procenta pro náhled nezávislá na velikosti okna', () => {
    expect(rectToPercent({ x: 250, y: 500, w: 500, h: 1000 }, 1000, 2000)).toEqual({
      left: '25%',
      top: '25%',
      width: '50%',
      height: '50%',
    });
  });
});

describe('scaledSize', () => {
  it('zmenší jen širší obrázek a zachová poměr stran', () => {
    expect(scaledSize(3200, 4000, 1600)).toEqual({ width: 1600, height: 2000 });
    expect(scaledSize(900, 3000, 1600)).toEqual({ width: 900, height: 3000 });
  });
});

describe('maskPixelRect', () => {
  it('zaokrouhlí okraje ven, i když šířka sama vychází celá', () => {
    // Pravý okraj 15,9 — zaokrouhlená šířka 5 od x = 10 by sloupec 15 nechala odkrytý.
    expect(maskPixelRect({ x: 10.9, y: 4.2, w: 5, h: 3 })).toEqual({ x: 10, y: 4, w: 6, h: 4 });
  });

  it('celé pixely nechá být', () => {
    expect(maskPixelRect({ x: 0, y: 1600, w: 1000, h: 400 })).toEqual({
      x: 0,
      y: 1600,
      w: 1000,
      h: 400,
    });
  });
});
