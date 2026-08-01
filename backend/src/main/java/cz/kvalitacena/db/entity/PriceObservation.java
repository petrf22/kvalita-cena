package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Jádro celé aplikace — viz docs/datovy-model.md. {@code unitPrice} je v databázi
 * {@code GENERATED ALWAYS ... STORED}, proto je tu {@code insertable/updatable = false};
 * po uložení nové observace je potřeba entitu znovu načíst, aby se hodnota promítla do Javy
 * (viz PriceObservationService).
 */
@Entity
@Table(name = "price_observation", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceObservation implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "product_id", nullable = false, foreignKey = @ForeignKey(name = "fk_price_observation_product"))
  private Product product;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "store_id", nullable = false, foreignKey = @ForeignKey(name = "fk_price_observation_store"))
  private Store store;

  @Column(name = "price_amount", nullable = false, precision = 10, scale = 2)
  private BigDecimal priceAmount;

  @Column(name = "currency", nullable = false, length = 3)
  @Builder.Default
  private String currency = "CZK";

  @Enumerated(EnumType.STRING)
  @Column(name = "price_kind", nullable = false, length = 12)
  @Builder.Default
  private PriceKind priceKind = PriceKind.REGULAR;

  @Enumerated(EnumType.STRING)
  @Column(name = "quantity_basis", nullable = false, length = 12)
  @Builder.Default
  private QuantityBasis quantityBasis = QuantityBasis.PACKAGE;

  @Column(name = "multibuy_qty")
  private Integer multibuyQty;

  @Column(name = "multibuy_total", precision = 10, scale = 2)
  private BigDecimal multibuyTotal;

  // SNAPSHOT gramáže/objemu produktu v době zápisu — viz docs/datovy-model.md.
  @Column(name = "net_content_base", nullable = false, precision = 12, scale = 6)
  private BigDecimal netContentBase;

  @Column(name = "unit_price", insertable = false, updatable = false, precision = 12, scale = 4)
  private BigDecimal unitPrice;

  @Column(name = "promo_valid_to")
  private LocalDate promoValidTo;

  @Column(name = "observed_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime observedAt;

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  // NULL po pseudonymizaci (180 dní) — viz docs/soukromi.md.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "submitter_id", foreignKey = @ForeignKey(name = "fk_price_observation_submitter"))
  private AppUser submitter;

  @Enumerated(EnumType.STRING)
  @Column(name = "submitter_kind", nullable = false, length = 12)
  private SubmitterKind submitterKind;

  @Column(name = "submitter_cohort")
  private Short submitterCohort;

  @Column(name = "frozen_weight", precision = 6, scale = 4)
  private BigDecimal frozenWeight;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 12)
  @Builder.Default
  private ObservationStatus status = ObservationStatus.ACTIVE;

  @Enumerated(EnumType.STRING)
  @Column(name = "agreement", nullable = false, length = 12)
  @Builder.Default
  private AgreementStatus agreement = AgreementStatus.PENDING;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 10)
  private ObservationSource source;

  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
