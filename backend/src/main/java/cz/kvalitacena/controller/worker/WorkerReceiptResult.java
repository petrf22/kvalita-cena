package cz.kvalitacena.controller.worker;

import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.QuantityUnit;
import cz.kvalitacena.db.entity.ReceiptProcessingStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Výsledek vytěžení jedné účtenky od workeru (docs/ai.md, „Účtenky"). {@code rawText}
 * a {@code extraction} (syrový JSON modelu) jdou do ai.receipt_job, zbytek je už normalizovaný
 * návrh pro core.receipt/core.receipt_line.
 */
public record WorkerReceiptResult(
    ReceiptProcessingStatus status,
    String model,
    String rawText,
    // Syrový JSON modelu jako mapa — nezávislé na verzi Jacksonu v HTTP konvertorech.
    Object extraction,
    String storeName,
    String storeAddress,
    String storeIco,
    OffsetDateTime purchasedAt,
    BigDecimal totalAmount,
    String currency,
    List<Line> lines) {

  public record Line(
      String label,
      String internalCode,
      BigDecimal quantity,
      QuantityUnit quantityUnit,
      BigDecimal unitPrice,
      BigDecimal lineTotal,
      BigDecimal discountAmount,
      PriceKind priceKind) {
  }
}
