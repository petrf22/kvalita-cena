package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;

/**
 * „Název na účtence" — označení zboží, jak ho tiskne obchod, → katalogová položka
 * (docs/datovy-model.md, „Účtenky"). Rozsah je právě jeden z {@code chainId}/{@code storeId}.
 * Do veřejného našeptávače názvů nepatří.
 */
@Entity
@Table(name = "product_store_label", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductStoreLabel implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "product_id", nullable = false)
  private Long productId;

  @Column(name = "label", nullable = false, length = 200)
  private String label;

  @Column(name = "label_norm", insertable = false, updatable = false, columnDefinition = "TEXT")
  private String labelNorm;

  @Column(name = "chain_id")
  private Long chainId;

  @Column(name = "store_id")
  private Long storeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 10)
  @Builder.Default
  private ProductAliasStatus status = ProductAliasStatus.PENDING;

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  @Column(name = "activated_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime activatedAt;

  @Column(name = "last_seen_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime lastSeenAt;

  @PrePersist
  void onCreate() {
    createdAt = OffsetDateTime.now();
    if (lastSeenAt == null) {
      lastSeenAt = createdAt;
    }
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
