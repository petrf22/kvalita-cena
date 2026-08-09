package cz.kvalitacena.service;

import java.util.Optional;

/**
 * Binární obsah fotek MIMO databázi (docs/datovy-model.md) — rozhraní přesně kvůli tomu, aby
 * šlo úložiště později vyměnit za S3/MinIO bez zásahu do {@link MediaService}, stejný vzor
 * jako {@code scanner/BarcodeScanner.kt} na mobilu nebo {@link OtpMailSender} tady v backendu.
 *
 * <p>{@code storageKey} se skládá VÝHRADNĚ na serveru (implementace), volající kód ho dostává
 * hotový a nikdy ho neposílá dovnitř zvenčí požadavku.
 */
public interface MediaStorage {

  enum Variant { FULL, THUMBNAIL }

  /**
   * Uloží obě varianty zpracovaného obrázku (viz {@link ImageProcessingService}) a vrátí nový
   * {@code storageKey}, pod kterým jsou obě dohledatelné.
   */
  String store(byte[] full, byte[] thumbnail);

  /** Prázdný výsledek, pokud soubor neexistuje (např. byl odstraněný mimo appku). */
  Optional<byte[]> load(String storageKey, Variant variant);

  /** Smaže obě varianty. Neexistující soubor není chyba (idempotentní). */
  void delete(String storageKey);
}
