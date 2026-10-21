package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;

/** Strojový token (docs/ai.md, „Účtenky") — v DB jen SHA-256, stroj nemá app_user. */
@Entity
@Table(name = "api_token", schema = "auth")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiToken implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "name", nullable = false, length = 100)
  private String name;

  @Column(name = "token_hash", nullable = false, unique = true)
  private byte[] tokenHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope", nullable = false, length = 20)
  private ApiTokenScope scope;

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  @Column(name = "last_used_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime lastUsedAt;

  @Column(name = "revoked_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime revokedAt;

  @PrePersist
  void onCreate() {
    createdAt = OffsetDateTime.now();
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
