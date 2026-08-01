package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Předpočítaný agregát pro aktuální cenu — tabulka, ne materialized view (docs/datovy-model.md),
 * protože váhy záznamů se mění zpětně a je potřeba cílený přepočet přes agg.recompute_queue.
 * V etapě 1 ji plní PriceAggregationService (viz task Agregace cen v0).
 */
@Entity
@Table(name = "price_current", schema = "agg")
@IdClass(PriceCurrentId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceCurrent {

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

  @Column(name = "unit_price", precision = 12, scale = 4)
  private BigDecimal unitPrice;

  @Column(name = "price_amount", precision = 10, scale = 2)
  private BigDecimal priceAmount;

  @Column(name = "n_obs", nullable = false)
  @Builder.Default
  private int nObs = 0;

  // Kishova efektivní velikost vzorku — obrana proti manipulaci, viz docs/reputace.md.
  @Column(name = "n_eff", nullable = false, precision = 8, scale = 3)
  @Builder.Default
  private BigDecimal nEff = BigDecimal.ZERO;

  @Column(name = "sum_weight", nullable = false, precision = 10, scale = 4)
  @Builder.Default
  private BigDecimal sumWeight = BigDecimal.ZERO;

  @Column(name = "last_observed_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime lastObservedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "confidence", nullable = false, length = 10)
  @Builder.Default
  private Confidence confidence = Confidence.LOW;
}
