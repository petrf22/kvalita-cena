/**
 * Normalizace čárového kódu na klientovi — jen číslice, bez vedoucích nul. Server normalizuje
 * na GTIN-14 (`backend/.../GtinNormalization.java`), appka tuhle logiku neduplikuje, jen
 * sjednocuje zápisy STEJNÉHO kódu pro cache klíč (`ProductService.lookupByCode`) a pro
 * porovnání zadaného kódu s nabídnutým OFF kandidátem (`product-form-validation.ts`).
 */
export function normalizeCode(code: string): string {
  const digits = code.replace(/\D/g, '');
  return digits.replace(/^0+(?=\d)/, '');
}
