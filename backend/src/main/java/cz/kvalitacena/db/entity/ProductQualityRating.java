package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;

/**
 * Hodnocení kvality zboží — jen hvězdičky 1–5 (5 nejlepší), bez textů. core.product
 * NENÍ core.product_review (etapa 2, viz docs/datovy-model.md) — žádná viditelnost, žádný
 * ViewerContext.
 *
 * <p>{@code productId}/{@code userId} jsou schválně skalární, ne {@code @ManyToOne} — entita se
 * nikdy nečte kvůli produktu ani uživateli, jen se do ní zapisuje a agreguje přes GROUP BY
 * ({@link cz.kvalitacena.db.repo.ProductQualityRatingRepository}).
 *
 * <p>Na rozdíl od {@link PriceObservation} se vazba na uživatele NEPSEUDONYMIZUJE po 180 dnech —
 * bez ní by nešlo vynutit "jedna známka na uživatele a produkt" (vědomé zhoršení proti
 * docs/soukromi.md, viz poznámka tamtéž).
 */
@Entity
@Table(name = "product_quality_rating", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductQualityRating implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "product_id", nullable = false)
  private Long productId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "stars", nullable = false)
  private short stars;

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
    updatedAt = createdAt;
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = OffsetDateTime.now();
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
