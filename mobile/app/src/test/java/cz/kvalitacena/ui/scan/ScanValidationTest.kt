package cz.kvalitacena.ui.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanValidationTest {

  @Test
  fun acceptsSupportedNumericCodes() {
    assertTrue(isManualBarcodeValid("12345678"))
    assertTrue(isManualBarcodeValid("8594001234567"))
    assertTrue(isManualBarcodeValid("08594001234567"))
  }

  @Test
  fun ignoresOuterWhitespace() {
    assertTrue(isManualBarcodeValid(" 8594001234567 "))
  }

  @Test
  fun rejectsWrongLengthOrNonDigits() {
    assertFalse(isManualBarcodeValid("1234567"))
    assertFalse(isManualBarcodeValid("123456789012345"))
    assertFalse(isManualBarcodeValid("8594-001234567"))
  }
}
