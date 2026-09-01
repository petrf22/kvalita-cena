package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;

/**
 * Nahlášení záznamu jako podezřelého/nesmyslného — hlasuje se o FAKTU, nikdy o ČLOVĚKU
 * (docs/reputace.md, "Nesouhlas se vyjadřuje k faktu, ne k člověku"), proto tu není žádný
 * "nahlášený uživatel", jen (typ, id) globálního záznamu. Po dosažení prahu
 * ({@code app.moderation.flags-to-hide}) nastaví {@link RecordFlagService} na cíli
 * {@code hidden_at}.
 *
 * <p>{@code userId} slouží výhradně k vynucení "jeden hlas na člověka a záznam"
 * (uq_record_flag_user) — z API ven nejde, stejně jako {@code product_review.user_id}
 * (docs/soukromi.md).
 */
@Entity
@Table(name = "record_flag", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecordFlag implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(name = "record_type", nullable = false, length = 10)
  private RecordType recordType;

  @Column(name = "record_id", nullable = false)
  private Long recordId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "reason", length = 500)
  private String reason;

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  // Vyplní ModerationService.resolveFlags — NULL = "čeká na přezkum" (docs/reputace.md,
  // "Moderace"). Bez tohohle nešlo vyřízené nahlášení odlišit od čekajícího.
  @Column(name = "resolved_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime resolvedAt;

  @Column(name = "resolved_by_user_id")
  private Long resolvedByUserId;

  @Enumerated(EnumType.STRING)
  @Column(name = "resolution", length = 10)
  private FlagResolution resolution;

  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
