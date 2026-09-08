package cz.kvalitacena.controller;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Normalizovaná účtenka posílaná na {@code POST /api/receipts} — formát {@code receipt-v1},
 * jehož kontrakt je v {@code tools/uctenky/schema/receipt-v1.json}.
 *
 * <p>Co tu ZÁMĚRNĚ není: cokoli z patičky účtenky kromě data a celkové částky. Číslo karty,
 * SEQ ID, autorizační kód, čísla pokladny a věrnostní údaje zahazuje už klientský parser,
 * takže se na server nikdy nedostanou (docs/soukromi.md, „Účtenka"). Není tu ani
 * {@code evidenceKind} ani zdroj zápisu — obojí určuje server podle cesty zápisu, protože
 * důkaz je násobič reputační váhy (docs/rozvoj.md).
 */
public record ReceiptDocument(
    Integer schemaVersion,
    String profile,
    Ocr ocr,
    Merchant merchant,
    OffsetDateTime purchasedAt,
    String currency,
    BigDecimal printedTotal,
    BigDecimal computedTotal,
    Boolean totalMatches,
    List<Line> lines,
    List<String> warnings) {

  public record Ocr(String model, String imageSha256, OffsetDateTime ocrAt) {
  }

  /** Hlavička účtenky — podklad pro DOHLEDÁNÍ provozovny, ne pro její automatické založení. */
  public record Merchant(String rawName, String chainSlug, String country, String street,
      String postalCode, String city) {
  }

  /**
   * {@code unitPrice} je u váhového zboží cena za kg/l, nikdy zaplacená částka — viz
   * {@code cz.kvalitacena.db.entity.ReceiptLine}.
   */
  public record Line(
      Integer lineNo,
      String kind,
      String rawLabel,
      String rawCode,
      BigDecimal quantity,
      String quantityBasis,
      BigDecimal unitPrice,
      BigDecimal lineTotal,
      String vatCode,
      Integer appliesToLineNo,
      String note) {
  }
}
