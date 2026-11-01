package cz.kvalitacena.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GtinTest {

  @Test
  fun acceptsGtinsWithValidCheckDigit() {
    assertTrue(isValidGtin("8594001740659")) // EAN-13
    assertTrue(isValidGtin("90169588")) // EAN-8
    assertTrue(isValidGtin("011210617001")) // UPC-A / GTIN-12
    assertTrue(isValidGtin("08594001740659")) // GTIN-14 (EAN-13 s vedoucí nulou)
  }

  @Test
  fun rejectsWrongCheckDigit() {
    assertFalse(isValidGtin("5997321796297"))
    assertFalse(isValidGtin("400990052165"))
  }

  @Test
  fun rejectsUnsupportedLength() {
    // Platný EAN-13 bez kontrolní číslice má 12 číslic — jako GTIN-12 ale kontrolou neprojde.
    assertFalse(isValidGtin("859400174065"))
    assertFalse(isValidGtin("1234567")) // 7 číslic
    assertFalse(isValidGtin("123456789")) // 9 číslic — vnitroobchodní kód, ne GTIN
    assertFalse(isValidGtin("085940017406590")) // 15 číslic
  }

  @Test
  fun rejectsEmptyAndNonDigits() {
    assertFalse(isValidGtin(""))
    assertFalse(isValidGtin("ABCDEFGH"))
    assertFalse(isValidGtin("859400174065X"))
    assertFalse(isValidGtin("8594-01740659"))
    // Arabsko-indické číslice projdou Char.isDigit(), GTIN to ale není.
    assertFalse(isValidGtin("٨٥٩٤٠٠١٧٤٠٦٥٩"))
  }

  @Test
  fun doesNotTrimInIsValidGtin() {
    assertFalse(isValidGtin(" 8594001740659 "))
  }

  @Test
  fun acceptsGlobalGtins() {
    assertTrue(isGlobalGtin("8594001740659"))
    assertTrue(isGlobalGtin("90169588"))
    assertTrue(isGlobalGtin("011210617001")) // 13místně 0011210617001 — prefix 00, ne 02/04
    assertTrue(isGlobalGtin("08594001740659"))
    assertTrue(isGlobalGtin("18594001740656")) // GTIN-14 s indikátorem balení 1
  }

  @Test
  fun rejectsRestrictedCirculation() {
    // Kontrolní číslice sedí, ale jde o kód s omezeným oběhem — do katalogu jako EAN nepatří.
    assertTrue(isValidGtin("2012345678903"))
    assertFalse(isGlobalGtin("2012345678903")) // váhová etiketa (prefix 20)
    assertFalse(isGlobalGtin("02012345678903")) // totéž jako GTIN-14 s vedoucí nulou
    assertFalse(isGlobalGtin("12012345678900")) // GTIN-14 s indikátorem 1 nad etiketou z vah
    assertFalse(isGlobalGtin("0512345678900")) // kupón UPC (05)
    assertFalse(isGlobalGtin("9812345678902")) // kupón
    assertFalse(isGlobalGtin("9912345678909")) // kupón
    assertFalse(isGlobalGtin("212345678992")) // UPC-A číselná soustava 2 → 13místně 02…
    assertFalse(isGlobalGtin("412345678903")) // UPC-A číselná soustava 4 → 13místně 04…
    assertFalse(isGlobalGtin("20000004")) // EAN-8 začínající 2
    assertFalse(isGlobalGtin("01234565")) // EAN-8 začínající 0
  }

  @Test
  fun rejectsInvalidCodesInIsGlobalGtin() {
    assertFalse(isGlobalGtin("5997321796297"))
    assertFalse(isGlobalGtin("400990052165"))
    assertFalse(isGlobalGtin("859400174065"))
    assertFalse(isGlobalGtin(""))
    assertFalse(isGlobalGtin("ABCDEFGHIJKLM"))
  }

  @Test
  fun globalGtinOrNullTrimsAndValidates() {
    assertEquals("8594001740659", globalGtinOrNull(" 8594001740659 "))
    assertEquals("90169588", globalGtinOrNull("90169588"))
    assertNull(globalGtinOrNull("2012345678903"))
    assertNull(globalGtinOrNull("5997321796297"))
    assertNull(globalGtinOrNull("12345"))
    assertNull(globalGtinOrNull(""))
    assertNull(globalGtinOrNull(null))
  }
}
