package cz.kvalitacena.db.entity;

/**
 * Na čem cena stojí (docs/rozvoj.md, „Zdroj ceny: kanál klienta vs. druh důkazu") — druhá osa
 * vedle {@link ObservationSource}. Nastavuje výhradně server podle skutečně připojeného
 * artefaktu, nikdy klient.
 */
public enum EvidenceKind {
  NONE,
  PRICE_TAG_PHOTO,
  RECEIPT_OCR
}
