package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Denní agregace pro graf vývoje ceny (agg.price_daily) — přesně stejný vzor jako
 * {@link PriceCurrent}, jen navíc klíčovaná dnem. Graf se čte VŽDY odsud, nikdy ze syrových
 * core.price_observation (docs/datovy-model.md). Plní {@link
 * cz.kvalitacena.service.PriceAggregationService#processQueue()}.
 */
@Entity
@Table(name = "price_daily", schema = "agg")
@IdClass(PriceDailyId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceDaily {

  @Id
  @Column(name = "product_id")
  private Long productId;

  @Id
  @Column(name = "store_id")
  private Long storeId;

  @Id
  @Enumerated(EnumType.STRING)
  @Column(name = "price_kind", length = 12)
  private PriceKind priceKind;

  // Součást PK, stejný důvod jako u PriceCurrent (viz 2026-08-09/01-agg-currency.yaml).
  @Id
  @Column(name = "currency", length = 3)
  private String currency;

  @Id
  @Column(name = "day")
  private LocalDate day;

  @Column(name = "unit_price", precision = 12, scale = 4)
  private BigDecimal unitPrice;

  @Column(name = "price_amount", precision = 10, scale = 2)
  private BigDecimal priceAmount;

  @Column(name = "n_obs", nullable = false)
  @Builder.Default
  private int nObs = 0;

  @Column(name = "n_eff", nullable = false, precision = 8, scale = 3)
  @Builder.Default
  private BigDecimal nEff = BigDecimal.ZERO;

  @Column(name = "sum_weight", nullable = false, precision = 10, scale = 4)
  @Builder.Default
  private BigDecimal sumWeight = BigDecimal.ZERO;

  @Column(name = "computed_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime computedAt;

  @PrePersist
  protected void onCreate() {
    computedAt = OffsetDateTime.now();
  }
}
