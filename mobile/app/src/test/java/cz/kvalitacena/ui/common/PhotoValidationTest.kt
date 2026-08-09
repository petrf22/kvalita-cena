package cz.kvalitacena.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    val error = photoValidationError("image/gif", 1024, 0)
    assertTrue(error != null && error.contains("JPEG nebo PNG"))
  }

  @Test
  fun rejectsMissingMimeType() {
    val error = photoValidationError(null, 1024, 0)
    assertTrue(error != null && error.contains("JPEG nebo PNG"))
  }

  @Test
  fun rejectsFilesOverSizeLimit() {
    val error = photoValidationError("image/jpeg", MAX_PHOTO_BYTES + 1, 0)
    assertTrue(error != null && error.contains("příliš velká"))
  }

  @Test
  fun rejectsOncePerRecordLimitReached() {
    val error = photoValidationError("image/jpeg", 1024, MAX_PHOTOS_PER_RECORD)
    assertTrue(error != null && error.contains("maximální počet"))
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
