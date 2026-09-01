package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;

/**
 * Hodnocení kvality zboží — jen hvězdičky 1–5 (5 nejlepší), zatím bez textů. Tabulka se dřív
 * jmenovala {@code product_quality_rating} — přejmenování na {@code product_review}
 * (2026-09-01/02-rename-product-review.yaml) předchází plánovanému přidání textu, aby název
 * tabulky neodporoval svému budoucímu obsahu (docs/datovy-model.md).
 *
 * <p>{@code productId}/{@code userId} jsou schválně skalární, ne {@code @ManyToOne} — entita se
 * nikdy nečte kvůli produktu ani uživateli, jen se do ní zapisuje a agreguje přes GROUP BY
 * ({@link cz.kvalitacena.db.repo.ProductReviewRepository}).
 *
 * <p>Na rozdíl od {@link PriceObservation} se vazba na uživatele NEPSEUDONYMIZUJE po 180 dnech —
 * bez ní by nešlo vynutit "jedna známka na uživatele a produkt" (vědomé zhoršení proti
 * docs/soukromi.md, viz poznámka tamtéž).
 */
@Entity
@Table(name = "product_review", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductReview implements Persistable<Long> {

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
