package cz.kvalitacena.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastStoreStoreTest {

  @Test
  fun obchodMladsiNezTricetDniZustane() {
    val day = 24L * 60 * 60 * 1000
    assertEquals("42", rememberedStoreId("42", 1_000, 1_000 + 29 * day))
  }

  @Test
  fun proslyNeboPoskozenyZaznamZmizi() {
    val day = 24L * 60 * 60 * 1000
    assertNull(rememberedStoreId("42", 1_000, 1_000 + 31 * day))
    assertNull(rememberedStoreId("42", 0, 1_000))
    assertNull(rememberedStoreId(null, 1_000, 1_000))
  }
}
