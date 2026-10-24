import { MaskRect, ReceiptPart, maskPixelRect, scaledSize } from './receipt-mask';

/**
 * Limity nahrání účtenky — zdroj pravdy je `app.receipt.*` na serveru (docs/ai.md, tabulka
 * „Prahy a limity"). Klient je hlídá jen kvůli včasné zpětné vazbě, server je kontroluje znovu.
 */
export const RECEIPT_MAX_WIDTH = 1600; // app.receipt.max-width
export const RECEIPT_MAX_IMAGE_BYTES = 4 * 1024 * 1024; // app.receipt.max-image-bytes
export const RECEIPT_MAX_PARTS = 30; // app.receipt.max-images-per-receipt

/** Kvalita JPEG při načtení — mezikrok, drží čitelnost drobného písma účtenky. */
const LOAD_QUALITY = 0.92;
/** Kvality pro odeslání, zkouší se postupně, dokud se část nevejde pod `RECEIPT_MAX_IMAGE_BYTES`. */
const EXPORT_QUALITIES = [0.9, 0.8, 0.7, 0.6];

/**
 * Barva zakrytí — přesně ta, která se vypálí do pixelů. Náhled v editoru ji ukazuje stejnou,
 * ať uživatel vidí, co opravdu odejde. Není to barva tématu, proto není v `--kc-*` tokenech.
 */
export const MASK_FILL = '#000';

/** Soubor nejde dekódovat (typicky HEIC v prohlížeči, který ho neumí). */
export class UnsupportedImageError extends Error {}

async function decode(file: Blob): Promise<ImageBitmap> {
  try {
    // from-image: otočení podle EXIF, jinak by fotka z telefonu mohla ležet na boku.
    return await createImageBitmap(file, { imageOrientation: 'from-image' });
  } catch {
    throw new UnsupportedImageError();
  }
}

function toBlob(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  return new Promise((resolve, reject) =>
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new UnsupportedImageError())),
      'image/jpeg',
      quality,
    ),
  );
}

/**
 * Načte vybranou fotku jako část účtenky: otočí podle EXIF, zmenší na šířku, kterou server
 * stejně uloží, a přeuloží přes canvas — tím zmizí i metadata (poloha z EXIF neopustí
 * prohlížeč, server je navíc strhává sám, docs/soukromi.md).
 */
export async function loadReceiptPart(file: File, id: number): Promise<ReceiptPart> {
  const bitmap = await decode(file);
  try {
    const size = scaledSize(bitmap.width, bitmap.height, RECEIPT_MAX_WIDTH);
    const canvas = document.createElement('canvas');
    canvas.width = size.width;
    canvas.height = size.height;
    const context = canvas.getContext('2d');
    if (!context) throw new UnsupportedImageError();
    context.drawImage(bitmap, 0, 0, size.width, size.height);
    const blob = await toBlob(canvas, LOAD_QUALITY);
    return {
      id,
      name: file.name,
      blob,
      url: URL.createObjectURL(blob),
      width: size.width,
      height: size.height,
      masks: [],
      masksTouched: false,
    };
  } finally {
    bitmap.close();
  }
}

/** Vypálí masky do pixelů a vrátí JPEG k odeslání — zakrytý obsah už v souboru není. */
export async function renderMaskedJpeg(part: ReceiptPart): Promise<Blob> {
  const bitmap = await decode(part.blob);
  try {
    const canvas = document.createElement('canvas');
    canvas.width = part.width;
    canvas.height = part.height;
    const context = canvas.getContext('2d');
    if (!context) throw new UnsupportedImageError();
    context.drawImage(bitmap, 0, 0, part.width, part.height);
    paintMasks(context, part.masks);
    let blob: Blob | null = null;
    for (const quality of EXPORT_QUALITIES) {
      blob = await toBlob(canvas, quality);
      if (blob.size <= RECEIPT_MAX_IMAGE_BYTES) return blob;
    }
    return blob!;
  } finally {
    bitmap.close();
  }
}

export function paintMasks(context: CanvasRenderingContext2D, masks: readonly MaskRect[]): void {
  context.fillStyle = MASK_FILL;
  for (const mask of masks) {
    const px = maskPixelRect(mask);
    context.fillRect(px.x, px.y, px.w, px.h);
  }
}
