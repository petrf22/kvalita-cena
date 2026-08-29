package cz.kvalitacena.ui.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductFormValidationTest {

  @Test
  fun emptyWhenNeitherPhotoWasPicked() {
    assertTrue(pendingPhotoUploads<String>(null, null).isEmpty())
  }

  @Test
  fun sendsOnlyItemPhotoWhenLabelWasNotPicked() {
    assertEquals(listOf(PendingPhotoUpload("item-uri", "ITEM")), pendingPhotoUploads("item-uri", null))
  }

  @Test
  fun sendsOnlyLabelPhotoWhenItemWasNotPicked() {
    assertEquals(listOf(PendingPhotoUpload("label-uri", "LABEL")), pendingPhotoUploads(null, "label-uri"))
  }

  @Test
  fun putsItemPhotoFirstSoItBecomesTheMainPhoto() {
    assertEquals(
      listOf(PendingPhotoUpload("item-uri", "ITEM"), PendingPhotoUpload("label-uri", "LABEL")),
      pendingPhotoUploads("item-uri", "label-uri"),
    )
  }
}
