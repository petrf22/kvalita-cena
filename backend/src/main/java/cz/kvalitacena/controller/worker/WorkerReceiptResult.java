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
      PriceKind priceKind,
      // Čitelný název a vodítko gramáže (tools/uctenky/pretty.py). Zdroj a jednotka jako text —
      // neznámá hodnota od novějšího workeru nesmí shodit celý výsledek, jen se zahodí.
      String labelDisplay,
      String labelDisplaySource,
      BigDecimal netContentValue,
      String netContentUom,
      // Návrh lokálního modelu pro formulář nového zboží (tools/uctenky/ai_classify.py) —
      // slug kategorie appky a jen rozměr gramáže (G/KG/ML/L). Poradní, server ho ještě ověří
      // a neznámou hodnotu zahodí (docs/ai.md, „Návrh kategorie a jednotky"). Typ Object
      // záměrně: cokoli jiného než text (seznam, objekt) se zahodí, nesmí shodit
      // deserializaci celého výsledku účtenky.
      Object categoryHint,
      Object unitHint) {

    /** Řádek bez návrhu modelu — starší worker, nebo model nedostupný. */
    public Line(String label, String internalCode, BigDecimal quantity, QuantityUnit quantityUnit,
        BigDecimal unitPrice, BigDecimal lineTotal, BigDecimal discountAmount, PriceKind priceKind,
        String labelDisplay, String labelDisplaySource, BigDecimal netContentValue, String netContentUom) {
      this(label, internalCode, quantity, quantityUnit, unitPrice, lineTotal, discountAmount, priceKind,
          labelDisplay, labelDisplaySource, netContentValue, netContentUom, null, null);
    }

    /** Návrh kategorie, jen když je to text. */
    public String categoryHintText() {
      return categoryHint instanceof String text ? text : null;
    }

    /** Návrh jednotky, jen když je to text. */
    public String unitHintText() {
      return unitHint instanceof String text ? text : null;
    }
  }
}
