package cz.kvalitacena.service;

import java.util.regex.Pattern;

/**
 * EAN → GTIN-14 normalizace (doplnění nulami zleva) — jediné místo, aby si skener v mobilu,
 * productByCode a createProduct rozuměly stejně (docs/datovy-model.md).
 */
public final class GtinNormalization {

  private static final int GTIN_14_LENGTH = 14;
  private static final Pattern VALID_CODE = Pattern.compile("[0-9]{8,14}");

  private GtinNormalization() {
  }

  /** Sdílí OpenFoodFactsService.lookup a productLookupByCode — stejný tvar kódu, jedna definice. */
  public static boolean isValidCode(String code) {
    return code != null && VALID_CODE.matcher(code.trim()).matches();
  }

  /**
   * Skutečný globální EAN/GTIN: délka 8/12/13/14, platná kontrolní číslice GS1 a ne kód
   * s omezeným oběhem. {@link #isValidCode} kontroluje jen tvar, a to na zápis do katalogu
   * nestačí. Kód přečtený OCR z účtenky má běžně chybu v jedné číslici a etiketa váhového
   * zboží z vah v obchodě (prefix 2) nese cenu, takže není identifikátorem zboží.
   * Zrcadlo klientů: {@code frontend/src/app/shared/gtin.ts}, {@code mobile/.../ui/common/Gtin.kt}.
   */
  public static boolean isGlobalGtin(String code) {
    if (code == null) return false;
    String digits = code.trim();
    if (!VALID_CODE.matcher(digits).matches() || digits.length() == 9 || digits.length() == 10
        || digits.length() == 11 || !hasValidCheckDigit(digits)) {
      return false;
    }
    return !isRestrictedCirculation(digits);
  }

  static boolean hasValidCheckDigit(String digits) {
    int sum = 0;
    for (int i = digits.length() - 2, weight = 3; i >= 0; i--, weight = 4 - weight) {
      sum += (digits.charAt(i) - '0') * weight;
    }
    return (10 - sum % 10) % 10 == digits.charAt(digits.length() - 1) - '0';
  }

  /** GS1 omezený oběh: EAN-8 s prefixem 0/2, GTIN-13 s prefixem 02, 04, 2x, 98, 99. */
  static boolean isRestrictedCirculation(String digits) {
    if (digits.length() == 8) {
      return digits.charAt(0) == '0' || digits.charAt(0) == '2';
    }
    String gtin13 = digits.length() == 14
        ? (digits.charAt(0) == '0' ? digits.substring(1) : null)
        : "0".repeat(13 - digits.length()) + digits;
    if (gtin13 == null) return false; // GTIN-14 s indikátorem balení — globální
    return gtin13.startsWith("2") || gtin13.startsWith("02") || gtin13.startsWith("04")
        || gtin13.startsWith("98") || gtin13.startsWith("99");
  }

  public static String toGtin14(String code) {
    String digits = code.trim();
    if (digits.length() >= GTIN_14_LENGTH) return digits;
    return "0".repeat(GTIN_14_LENGTH - digits.length()) + digits;
  }
}
