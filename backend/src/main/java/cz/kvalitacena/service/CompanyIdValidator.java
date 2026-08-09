package cz.kvalitacena.service;

/**
 * Validace tvaru identifikačního čísla firmy — algoritmus se liší podle země (docs/lokalizace.md).
 * Pole {@code ico} v GraphQL se kvůli historii nepřejmenovává, i když nese IČO (CZ/SK) i NIP
 * (PL) — popisek v UI je proto per-country klíč (`store.form.companyId.label.{country}`), ne
 * z názvu pole.
 */
public interface CompanyIdValidator {
  String country();

  boolean isValid(String value);
}
