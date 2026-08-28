package cz.kvalitacena.ui.common

/**
 * Normalizace čárového kódu na klientovi — jen číslice, bez vedoucích nul. Server normalizuje
 * na GTIN-14 (`backend/.../GtinNormalization.java`), appka tuhle logiku neduplikuje, jen
 * sjednocuje zápisy STEJNÉHO kódu pro cache klíč (`network/GraphQlClient.kt`) a pro porovnání
 * zadaného kódu s nabídnutým OFF kandidátem (`ui/product/ProductFormViewModel.kt`), zrcadlo
 * webu (`frontend/src/app/shared/gtin.ts`).
 */
fun normalizeCode(code: String): String {
  val digits = code.filter { it.isDigit() }
  return digits.trimStart('0').ifEmpty { if (digits.isNotEmpty()) "0" else "" }
}
