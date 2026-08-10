/**
 * Čistá klientská validace uploadu fotky — jen včasná zpětná vazba v UI, ne zdroj pravdy.
 * Backend (ImageProcessingService/MediaService) validuje znovu a přísněji (magic bytes místo
 * Content-Type, rozměry, denní limit) — appka tu jen ušetří uživateli zbytečný upload.
 */

export const ALLOWED_PHOTO_TYPES = ['image/jpeg', 'image/png'];
export const MAX_PHOTO_BYTES = 8 * 1024 * 1024;
export const MAX_PHOTOS_PER_RECORD = 5;

/**
 * Stejné kódy jako `ErrorCode` na backendu (docs/lokalizace.md) — appka tu jen ušetří
 * uživateli zbytečný upload, ale text hlášky se skládá až na volající straně přes
 * `errors.PHOTO_*` z kořenového bundlu, aby klient neduplikoval vlastní český text.
 */
export type PhotoValidationErrorCode =
  'PHOTO_LIMIT_REACHED' | 'PHOTO_UNSUPPORTED_FORMAT' | 'PHOTO_TOO_LARGE';

/** Kód chyby, nebo null, pokud je soubor v pořádku k odeslání. */
export function photoValidationError(
  file: File,
  existingPhotoCount: number,
): PhotoValidationErrorCode | null {
  if (existingPhotoCount >= MAX_PHOTOS_PER_RECORD) {
    return 'PHOTO_LIMIT_REACHED';
  }
  if (!ALLOWED_PHOTO_TYPES.includes(file.type)) {
    return 'PHOTO_UNSUPPORTED_FORMAT';
  }
  if (file.size > MAX_PHOTO_BYTES) {
    return 'PHOTO_TOO_LARGE';
  }
  return null;
}

export function remainingPhotoSlots(existingPhotoCount: number): number {
  return Math.max(0, MAX_PHOTOS_PER_RECORD - existingPhotoCount);
}
