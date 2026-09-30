package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;

/**
 * Úloha vytěžení účtenky pro pull worker (schéma {@code ai}, docs/ai.md). Syrový výstup
 * modelu ({@code rawText}, {@code extraction}) zůstává tady, mimo core.
 */
@Entity
@Table(name = "receipt_job", schema = "ai")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiptJob implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "receipt_id", nullable = false)
  private Long receiptId;

  @Column(name = "enqueued_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime enqueuedAt;

  @Column(name = "leased_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime leasedAt;

  @Column(name = "lease_until", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime leaseUntil;

  @Column(name = "attempts", nullable = false)
  @Builder.Default
  private Short attempts = 0;

  @Column(name = "processed_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime processedAt;

  @Column(name = "model", length = 100)
  private String model;

  @Column(name = "raw_text", columnDefinition = "TEXT")
  private String rawText;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "extraction", columnDefinition = "JSONB")
  private String extraction;

  @Column(name = "last_error", length = 500)
  private String lastError;

  @PrePersist
  void onCreate() {
    if (enqueuedAt == null) {
      enqueuedAt = OffsetDateTime.now();
    }
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
