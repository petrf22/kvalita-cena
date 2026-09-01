package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;

/**
 * Hodnocení kvality zboží — hvězdičky 1–5 (5 nejlepší) povinně, text recenze volitelně (max
 * 1000 znaků, CHECK v DB). Jeden záznam, ne dvě entity — text bez hvězdiček nedává smysl a dvě
 * tabulky by znamenaly dva zdroje pravdy pro totéž hodnocení (docs/datovy-model.md). Tabulka se
 * dřív jmenovala {@code product_quality_rating} a nesla jen hvězdičky, viz
 * 2026-09-01/02-rename-product-review.yaml a 2026-09-01/03-product-review-text.yaml.
 *
 * <p>{@code productId}/{@code userId} jsou schválně skalární, ne {@code @ManyToOne} — entita se
 * nikdy nečte kvůli produktu ani uživateli, jen se do ní zapisuje a agreguje přes GROUP BY
 * ({@link cz.kvalitacena.db.repo.ProductReviewRepository}).
 *
 * <p>Na rozdíl od {@link PriceObservation} se vazba na uživatele NEPSEUDONYMIZUJE po 180 dnech —
 * bez ní by nešlo vynutit "jedno hodnocení na uživatele a produkt" (vědomé zhoršení proti
 * docs/soukromi.md, viz poznámka tamtéž). Smazání účtu tak řádek (hvězdičky i text) rovnou
 * odstraní kaskádou (fk_product_review_user ON DELETE CASCADE), ne anonymizuje.
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

  /** Volitelný text recenze, max 1000 znaků (chk_product_review_text_length). Null = jen hvězdičky. */
  @Column(name = "text", columnDefinition = "TEXT")
  private String text;

  /**
   * Kdy se naposledy změnil {@link #text} — na rozdíl od {@link #updatedAt} se NEHÝBE při
   * pouhé změně hvězdiček, takže klient podle něj (ne podle updatedAt) pozná štítek "upraveno".
   */
  @Column(name = "text_updated_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime textUpdatedAt;

  /** Skrytí moderací (core.record_flag, RecordType.REVIEW) — stejný vzor jako Media.hiddenAt. */
  @Column(name = "hidden_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime hiddenAt;

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

  public boolean isHidden() {
    return hiddenAt != null;
  }
}
