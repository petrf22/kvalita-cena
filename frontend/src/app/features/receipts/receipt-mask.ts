/**
 * Geometrie zakrytí citlivých údajů na účtence (docs/soukromi.md, „Účtenky" — maska se vypálí
 * do pixelů v prohlížeči, na server zakrytý obsah vůbec nedorazí). Jen čisté funkce nad čísly,
 * vykreslení na canvas je v `receipt-image.ts`.
 *
 * Obdélníky jsou v pixelech UŽ ZMENŠENÉHO obrázku (`ReceiptPart.width/height`), ne v pixelech
 * obrazovky — editor si je při kreslení přepočítá podle aktuální šířky náhledu, takže změna
 * velikosti okna masku neposune.
 */

export interface MaskRect {
  x: number;
  y: number;
  w: number;
  h: number;
  /**
   * Výchozí návrh (spodní pás), na který uživatel zatím nesáhl — posouvá se s poslední částí
   * účtenky, když se pořadí změní. Jakmile ho uživatel upraví, stane se obyčejnou maskou.
   */
  auto?: boolean;
}

/** Jedna část účtenky připravená k odeslání (výřez shora dolů). */
export interface ReceiptPart {
  id: number;
  name: string;
  /** Zmenšený JPEG bez metadat (EXIF včetně polohy se na canvasu nepřenese). */
  blob: Blob;
  /** Object URL `blob` pro náhled — po odebrání části se musí uvolnit. */
  url: string;
  width: number;
  height: number;
  masks: MaskRect[];
  /** Uživatel masky téhle části upravoval — výchozí pás se jí pak už nepřidává. */
  masksTouched: boolean;
}

/**
 * Výchozí návrh zakrytí: spodní pětina POSLEDNÍ části. Pod celkovou částkou bývá číslo karty,
 * věrnostní karta a jméno pokladní (docs/soukromi.md); kde přesně, appka bez OCR neví, takže
 * nabízí pás, který uživatel posune. Větší pás by u krátké účtenky snadno zakryl i položky.
 */
export const DEFAULT_BAND_FRACTION = 0.2;

/** Nejmenší maska v pixelech obrázku — kratší tah je spíš omylem kliknutí než zakrytí. */
export const MIN_MASK_PX = 12;

export function defaultBand(width: number, height: number): MaskRect {
  const h = Math.max(MIN_MASK_PX, Math.round(height * DEFAULT_BAND_FRACTION));
  return { x: 0, y: height - h, w: width, h, auto: true };
}

/**
 * Výchozí pás patří jen poslední části — u dlouhé účtenky focené po kusech by pás na každé
 * části zakryl položky v překryvu. Po přidání, odebrání nebo přeřazení částí se proto pás
 * přestěhuje na novou poslední část, pokud ji uživatel ještě neupravoval.
 */
export function withDefaultBand(parts: readonly ReceiptPart[]): ReceiptPart[] {
  const lastIndex = parts.length - 1;
  return parts.map((part, index) => {
    const own = part.masks.filter((mask) => !mask.auto);
    const wantsBand = index === lastIndex && !part.masksTouched;
    const band = part.masks.find((mask) => mask.auto) ?? defaultBand(part.width, part.height);
    const masks = wantsBand ? [...own, band] : own;
    const unchanged =
      masks.length === part.masks.length && masks.every((mask, i) => mask === part.masks[i]);
    return unchanged ? part : { ...part, masks };
  });
}

/** Posun části o jedno místo nahoru (-1) nebo dolů (+1); mimo rozsah vrátí pole beze změny. */
export function movePart(
  parts: readonly ReceiptPart[],
  id: number,
  direction: -1 | 1,
): ReceiptPart[] {
  const index = parts.findIndex((part) => part.id === id);
  const target = index + direction;
  if (index < 0 || target < 0 || target >= parts.length) return [...parts];
  const next = [...parts];
  [next[index], next[target]] = [next[target], next[index]];
  return next;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

/** Obdélník ze dvou rohů tahu (v libovolném směru), oříznutý do obrázku. */
export function rectFromPoints(
  x1: number,
  y1: number,
  x2: number,
  y2: number,
  width: number,
  height: number,
): MaskRect {
  const left = clamp(Math.min(x1, x2), 0, width);
  const top = clamp(Math.min(y1, y2), 0, height);
  const right = clamp(Math.max(x1, x2), 0, width);
  const bottom = clamp(Math.max(y1, y2), 0, height);
  return { x: left, y: top, w: right - left, h: bottom - top };
}

/**
 * Obdélník masky v celých pixelech, zaokrouhlený VEN na všech stranách — pravý a dolní okraj
 * se počítají z `x + w`/`y + h`, ne zaokrouhlením šířky zvlášť. Jinak by u masky třeba
 * x = 10,9, w = 5 zůstal odkrytý sloupec 15 a na server by dorazil pruh čísla karty.
 * Stejné pravidlo jako `maskToPixels` na mobilu.
 */
export function maskPixelRect(mask: MaskRect): { x: number; y: number; w: number; h: number } {
  const x = Math.floor(mask.x);
  const y = Math.floor(mask.y);
  return { x, y, w: Math.ceil(mask.x + mask.w) - x, h: Math.ceil(mask.y + mask.h) - y };
}

export function isUsableMask(rect: MaskRect): boolean {
  return rect.w >= MIN_MASK_PX && rect.h >= MIN_MASK_PX;
}

/** Posun celé masky; drží ji celou uvnitř obrázku. Upravená maska přestává být „auto". */
export function moveRect(
  rect: MaskRect,
  dx: number,
  dy: number,
  width: number,
  height: number,
): MaskRect {
  return {
    x: clamp(rect.x + dx, 0, width - rect.w),
    y: clamp(rect.y + dy, 0, height - rect.h),
    w: rect.w,
    h: rect.h,
  };
}

export type Corner = 'nw' | 'ne' | 'sw' | 'se';

/** Tažení za roh — protější roh zůstává na místě, maska nejde zmenšit pod `MIN_MASK_PX`. */
export function resizeRect(
  rect: MaskRect,
  corner: Corner,
  dx: number,
  dy: number,
  width: number,
  height: number,
): MaskRect {
  let left = rect.x;
  let top = rect.y;
  let right = rect.x + rect.w;
  let bottom = rect.y + rect.h;
  if (corner === 'nw' || corner === 'sw') left = clamp(left + dx, 0, right - MIN_MASK_PX);
  if (corner === 'ne' || corner === 'se') right = clamp(right + dx, left + MIN_MASK_PX, width);
  if (corner === 'nw' || corner === 'ne') top = clamp(top + dy, 0, bottom - MIN_MASK_PX);
  if (corner === 'sw' || corner === 'se') bottom = clamp(bottom + dy, top + MIN_MASK_PX, height);
  return { x: left, y: top, w: right - left, h: bottom - top };
}

/** Maska v procentech náhledu (CSS `left/top/width/height`) — náhled se škáluje s oknem. */
export function rectToPercent(
  rect: MaskRect,
  width: number,
  height: number,
): { left: string; top: string; width: string; height: string } {
  const pct = (value: number, total: number) => `${(value / total) * 100}%`;
  return {
    left: pct(rect.x, width),
    top: pct(rect.y, height),
    width: pct(rect.w, width),
    height: pct(rect.h, height),
  };
}

/**
 * Cílové rozměry při načtení fotky — šířka nejvýš `maxWidth` (server víc stejně neuloží,
 * `app.receipt.max-width` v docs/ai.md), výška se dopočítá v poměru stran.
 */
export function scaledSize(
  width: number,
  height: number,
  maxWidth: number,
): { width: number; height: number } {
  if (width <= maxWidth) return { width, height };
  const scale = maxWidth / width;
  return { width: maxWidth, height: Math.round(height * scale) };
}
