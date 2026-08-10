package cz.kvalitacena.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoValidationTest {

  @Test
  fun acceptsNormalJpegUnderSizeLimit() {
    assertNull(photoValidationError("image/jpeg", 1024, 0))
  }

  @Test
  fun acceptsPngToo() {
    assertNull(photoValidationError("image/png", 1024, 0))
  }

  @Test
  fun rejectsUnsupportedTypes() {
    assertEquals(PhotoValidationError.UNSUPPORTED_FORMAT, photoValidationError("image/gif", 1024, 0))
  }

  @Test
  fun rejectsMissingMimeType() {
    assertEquals(PhotoValidationError.UNSUPPORTED_FORMAT, photoValidationError(null, 1024, 0))
  }

  @Test
  fun rejectsFilesOverSizeLimit() {
    assertEquals(PhotoValidationError.TOO_LARGE, photoValidationError("image/jpeg", MAX_PHOTO_BYTES + 1, 0))
  }

  @Test
  fun rejectsOncePerRecordLimitReached() {
    assertEquals(
      PhotoValidationError.LIMIT_REACHED,
      photoValidationError("image/jpeg", 1024, MAX_PHOTOS_PER_RECORD),
    )
  }

  @Test
  fun remainingSlotsCountDownFromLimit() {
    assertEquals(MAX_PHOTOS_PER_RECORD, remainingPhotoSlots(0))
    assertEquals(1, remainingPhotoSlots(MAX_PHOTOS_PER_RECORD - 1))
  }

  @Test
  fun remainingSlotsNeverGoNegative() {
    assertEquals(0, remainingPhotoSlots(MAX_PHOTOS_PER_RECORD + 3))
  }
}
