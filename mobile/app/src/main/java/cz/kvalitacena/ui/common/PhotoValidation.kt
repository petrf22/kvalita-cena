package cz.kvalitacena.ui.common

/**
 * Čistá klientská validace uploadu fotky — mimo Compose, ať jde otestovat JUnitem (stejný
 * vzor jako StoreFormValidation/PriceChartGeometry). Jen včasná zpětná vazba v UI, ne zdroj
 * pravdy — backend (ImageProcessingService/MediaService) validuje znovu a přísněji (magic
 * bytes místo Content-Type, rozměry, denní limit), stejný princip jako frontend
 * shared/photo-upload-validation.ts.
 */

const val MAX_PHOTO_BYTES = 8L * 1024 * 1024
const val MAX_PHOTOS_PER_RECORD = 5
val ALLOWED_PHOTO_MIME_TYPES = setOf("image/jpeg", "image/png")

/** Stejný vzor jako web `photo-upload-validation.ts` (docs/lokalizace.md) — kód místo
 * hotové věty, text se skládá až u volajícího přes `UiText` (`PhotoPicker.kt`). */
enum class PhotoValidationError { LIMIT_REACHED, UNSUPPORTED_FORMAT, TOO_LARGE }

/** Kód chyby, nebo null, pokud je soubor v pořádku k odeslání. */
fun photoValidationError(mimeType: String?, byteSize: Long, existingPhotoCount: Int): PhotoValidationError? {
  if (existingPhotoCount >= MAX_PHOTOS_PER_RECORD) {
    return PhotoValidationError.LIMIT_REACHED
  }
  if (mimeType == null || mimeType !in ALLOWED_PHOTO_MIME_TYPES) {
    return PhotoValidationError.UNSUPPORTED_FORMAT
  }
  if (byteSize > MAX_PHOTO_BYTES) {
    return PhotoValidationError.TOO_LARGE
  }
  return null
}

fun remainingPhotoSlots(existingPhotoCount: Int): Int =
  (MAX_PHOTOS_PER_RECORD - existingPhotoCount).coerceAtLeast(0)
