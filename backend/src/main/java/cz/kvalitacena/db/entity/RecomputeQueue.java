package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;

@Entity
@Table(name = "recompute_queue", schema = "agg")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecomputeQueue implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "product_id", nullable = false)
  private Long productId;

  @Column(name = "store_id", nullable = false)
  private Long storeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason", nullable = false, length = 20)
  private RecomputeReason reason;

  @Column(name = "enqueued_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime enqueuedAt;

  @Column(name = "processed_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime processedAt;

  @PrePersist
  protected void onCreate() {
    enqueuedAt = OffsetDateTime.now();
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
