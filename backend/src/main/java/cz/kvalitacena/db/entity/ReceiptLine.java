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

  /** Čitelný název od workeru — jen pro oči a hledání, mapování se učí podle {@code labelRaw}. */
  @Column(name = "label_display", length = 200)
  private String labelDisplay;

  @Enumerated(EnumType.STRING)
  @Column(name = "label_display_source", length = 5)
  private LabelDisplaySource labelDisplaySource;

  /** Vodítko gramáže z označení („340G") pro automatický odhad — nikdy hodnota zboží. */
  @Column(name = "net_content_hint_value", precision = 12, scale = 3)
  private BigDecimal netContentHintValue;

  @Enumerated(EnumType.STRING)
  @Column(name = "net_content_hint_uom", length = 2)
  private NetContentUom netContentHintUom;

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

  /**
   * Cena z řádku je zapsaná. Pseudonymizace po 180 dnech (docs/soukromi.md, „Účtenky")
   * {@code observationId} vynuluje, řádek ale zůstane MATCHED bez {@code skipReason} — a musí
   * zůstat zamčený, jinak by šel potvrdit znovu a vznikla by nová cena s autorem. MATCHED se
   * {@code skipReason} je duplicita téhož dne, ze které cena nevznikla.
   */
  public boolean isConfirmed() {
    return observationId != null || (status == ReceiptLineStatus.MATCHED && skipReason == null);
  }

  /**
   * Na řádek sáhl uživatel — potvrdil cenu, vybral nebo založil zboží, nebo ho ignoruje.
   * Takový řádek stroj nikdy nepřepisuje, ani při novém rozpoznání účtenky (docs/ai.md,
   * „Nové rozpoznání").
   */
  public boolean isUserTouched() {
    return isConfirmed()
        || status == ReceiptLineStatus.MATCHED
        || status == ReceiptLineStatus.IGNORED
        || matchSource == ReceiptMatchSource.USER
        || matchSource == ReceiptMatchSource.NEW_PRODUCT;
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
