package cz.kvalitacena.ui.common

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeDateTest {

  @Test
  fun unparsableIsoIsReturnedUnchanged() {
    assertEquals("not-a-date", relativeDateIn("not-a-date", Locale.forLanguageTag("cs")))
  }
}
