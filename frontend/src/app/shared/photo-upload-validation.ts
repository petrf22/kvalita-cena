/**
 * Čistá klientská validace uploadu fotky — jen včasná zpětná vazba v UI, ne zdroj pravdy.
 * Backend (ImageProcessingService/MediaService) validuje znovu a přísněji (magic bytes místo
 * Content-Type, rozměry, denní limit) — appka tu jen ušetří uživateli zbytečný upload.
 */

export const ALLOWED_PHOTO_TYPES = ['image/jpeg', 'image/png'];
export const MAX_PHOTO_BYTES = 8 * 1024 * 1024;
export const MAX_PHOTOS_PER_RECORD = 5;

/** Text chyby, nebo null, pokud je soubor v pořádku k odeslání. */
export function photoValidationError(file: File, existingPhotoCount: number): string | null {
  if (existingPhotoCount >= MAX_PHOTOS_PER_RECORD) {
    return `Záznam už má maximální počet fotek (${MAX_PHOTOS_PER_RECORD}).`;
  }
  if (!ALLOWED_PHOTO_TYPES.includes(file.type)) {
    return 'Podporované jsou jen fotky JPEG nebo PNG.';
  }
  if (file.size > MAX_PHOTO_BYTES) {
    return 'Fotka je příliš velká (max. 8 MB).';
  }
  return null;
}

export function remainingPhotoSlots(existingPhotoCount: number): number {
  return Math.max(0, MAX_PHOTOS_PER_RECORD - existingPhotoCount);
}
