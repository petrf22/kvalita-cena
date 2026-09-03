package cz.kvalitacena.ui.scan

private val SUPPORTED_BARCODE_LENGTHS = 8..14

/** Ruční vstup přijímá stejné číselné EAN/GTIN délky, které umí vyhledat backend. */
fun isManualBarcodeValid(value: String): Boolean {
  val code = value.trim()
  return code.length in SUPPORTED_BARCODE_LENGTHS && code.all(Char::isDigit)
}
