package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Řádek účtenky (docs/datovy-model.md, „Účtenky"). {@code unitPrice} je u váhového zboží cena
 * za kg/l, NIKDY zaplacená částka — ta je v {@code lineTotal}. {@code labelNorm} je v DB
 * {@code GENERATED ALWAYS ... STORED} (core.norm_label).
 */
@Entity
@Table(name = "receipt_line", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiptLine implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "receipt_id", nullable = false)
  private Long receiptId;

  @Column(name = "line_no", nullable = false)
  private Short lineNo;

  @Column(name = "label_raw", nullable = false, length = 200)
  private String labelRaw;

  @Column(name = "label_norm", insertable = false, updatable = false, columnDefinition = "TEXT")
  private String labelNorm;

  @Column(name = "internal_code", length = 40)
  private String internalCode;

  @Column(name = "quantity", precision = 10, scale = 3)
  private BigDecimal quantity;

  @Enumerated(EnumType.STRING)
  @Column(name = "quantity_unit", nullable = false, length = 4)
  @Builder.Default
  private QuantityUnit quantityUnit = QuantityUnit.PCS;

  @Column(name = "unit_price", precision = 10, scale = 2)
  private BigDecimal unitPrice;

  @Column(name = "line_total", precision = 10, scale = 2)
  private BigDecimal lineTotal;

  @Column(name = "discount_amount", precision = 10, scale = 2)
  private BigDecimal discountAmount;

  @Enumerated(EnumType.STRING)
  @Column(name = "price_kind_guess", nullable = false, length = 12)
  @Builder.Default
  private PriceKind priceKindGuess = PriceKind.REGULAR;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 10)
  @Builder.Default
  private ReceiptLineStatus status = ReceiptLineStatus.UNMATCHED;

  @Column(name = "product_id")
  private Long productId;

  @Enumerated(EnumType.STRING)
  @Column(name = "match_source", length = 12)
  private ReceiptMatchSource matchSource;

  @Column(name = "observation_id")
  private Long observationId;

  @Column(name = "skip_reason", length = 30)
  private String skipReason;

  @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime updatedAt;

  @PrePersist
  @PreUpdate
  void touch() {
    updatedAt = OffsetDateTime.now();
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
