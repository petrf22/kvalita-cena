package cz.kvalitacena.service;

/**
 * EAN → GTIN-14 normalizace (doplnění nulami zleva) — jediné místo, aby si skener v mobilu,
 * productByCode a createProduct rozuměly stejně (docs/datovy-model.md).
 */
public final class GtinNormalization {

  private static final int GTIN_14_LENGTH = 14;

  private GtinNormalization() {
  }

  public static String toGtin14(String code) {
    String digits = code.trim();
    if (digits.length() >= GTIN_14_LENGTH) return digits;
    return "0".repeat(GTIN_14_LENGTH - digits.length()) + digits;
  }
}
