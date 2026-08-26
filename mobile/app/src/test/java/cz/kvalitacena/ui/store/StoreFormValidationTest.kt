package cz.kvalitacena.ui.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kontrolní součet IČO se na klientovi NEDUPLIKUJE (viz plán projektu) — testuje se jen tvar
 * (8 číslic), skutečný kontrolní součet je jen na serveru (IcoValidator).
 */
class StoreFormValidationTest {

  @Test
  fun formIsValidOnlyWithNameAndCity() {
    assertTrue(isStoreFormValid("Obchod", "Brno"))
    assertFalse(isStoreFormValid("", "Brno"))
    assertFalse(isStoreFormValid("Obchod", ""))
    assertFalse(isStoreFormValid("  ", "  "))
  }

  @Test
  fun blankIcoIsValid() {
    assertTrue(isIcoShapeValid(""))
  }

  @Test
  fun icoMustBeEightDigits() {
    assertTrue(isIcoShapeValid("12345679"))
    assertFalse(isIcoShapeValid("1234567"))
    assertFalse(isIcoShapeValid("123456789"))
    assertFalse(isIcoShapeValid("1234567X"))
  }

  @Test
  fun blankUrlIsValid() {
    assertTrue(isUrlShapeValid(""))
  }

  @Test
  fun urlMustBeHttpOrHttps() {
    assertTrue(isUrlShapeValid("https://www.example.cz/prodejna"))
    assertTrue(isUrlShapeValid("http://example.cz"))
    assertFalse(isUrlShapeValid("ftp://example.cz"))
    assertFalse(isUrlShapeValid("not-a-url"))
    assertFalse(isUrlShapeValid("javascript:alert(1)"))
  }
}
