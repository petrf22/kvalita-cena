package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;

/**
 * Návrh kategorie a jednotky nového zboží k řádku účtenky od lokálního modelu (schéma
 * {@code ai}, docs/ai.md, „Návrh kategorie a jednotky"). Poradní údaj — jen předvyplní
 * formulář ({@code NewProductDefaultsService}), nikdy nezakládá zboží.
 */
@Entity
@Table(name = "receipt_line_hint", schema = "ai")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiptLineHint implements Persistable<Long> {

  @Id
  @Column(name = "line_id")
  private Long lineId;

  @Column(name = "category_id")
  private Long categoryId;

  /** Jen rozměr (G/KG/ML/L) — číslo model nikdy nedodává. */
  @Enumerated(EnumType.STRING)
  @Column(name = "net_content_uom", length = 2)
  private NetContentUom netContentUom;

  @Column(name = "model", length = 100)
  private String model;

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  /** Klíč je id řádku, ne generované — nový záznam se pozná podle příznaku, ne podle id. */
  @Transient
  @Builder.Default
  private boolean fresh = true;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = OffsetDateTime.now();
    }
  }

  @PostLoad
  void onLoad() {
    fresh = false;
  }

  @Override
  public Long getId() {
    return lineId;
  }

  @Override
  public boolean isNew() {
    return fresh;
  }
}
