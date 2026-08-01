package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Jeden refresh token v řetězu rotací. {@code familyUid} je společný pro celou rodinu —
 * při detekci znovupoužití starého tokenu (reuse) se revokuje celá rodina najednou,
 * ne jen jeden token (docs/soukromi.md, plán projektu — Passwordless auth).
 */
@Entity
@Table(name = "refresh_token", schema = "auth")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "family_uid", nullable = false, columnDefinition = "UUID")
  private UUID familyUid;

  // SHA-256 syrového tokenu — syrový token se nikdy neukládá, jen jeho hash.
  @Column(name = "token_hash", nullable = false, unique = true)
  private byte[] tokenHash;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_refresh_token_user"))
  private AppUser user;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_id", foreignKey = @ForeignKey(name = "fk_refresh_token_parent"))
  private RefreshToken parent;

  @Enumerated(EnumType.STRING)
  @Column(name = "client_kind", nullable = false, length = 10)
  private ClientKind clientKind;

  @Column(name = "device_label", length = 60)
  private String deviceLabel;

  @Column(name = "issued_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime issuedAt;

  @Column(name = "expires_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime expiresAt;

  @Column(name = "used_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime usedAt;

  @Column(name = "revoked_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime revokedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "revoke_reason", length = 20)
  private RevokeReason revokeReason;

  @PrePersist
  protected void onCreate() {
    issuedAt = OffsetDateTime.now();
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
