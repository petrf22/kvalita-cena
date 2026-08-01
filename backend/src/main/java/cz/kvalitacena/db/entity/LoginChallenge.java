package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Jedna přihlašovací výzva (OTP). Znalost {@code challengeUid} je nutná podmínka pro ověření
 * kódu — proto samotná znalost cizího e-mailu k přihlášení nestačí (docs/soukromi.md).
 */
@Entity
@Table(name = "login_challenge", schema = "auth")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginChallenge implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "challenge_uid", nullable = false, unique = true, columnDefinition = "UUID")
  private UUID challengeUid;

  @Column(name = "email_hash", nullable = false)
  private byte[] emailHash;

  // Argon2id hash kódu (textová podoba — Argon2 kóduje parametry i sůl do stringu).
  @Column(name = "code_hash", nullable = false)
  private String codeHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "purpose", nullable = false, length = 20)
  @Builder.Default
  private ChallengePurpose purpose = ChallengePurpose.LOGIN;

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  @Column(name = "expires_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime expiresAt;

  @Column(name = "consumed_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime consumedAt;

  @Column(name = "attempts", nullable = false)
  @Builder.Default
  private short attempts = 0;

  @Column(name = "max_attempts", nullable = false)
  @Builder.Default
  private short maxAttempts = 5;

  @Column(name = "ip_hash")
  private byte[] ipHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "client_kind", nullable = false, length = 10)
  private ClientKind clientKind;

  @PrePersist
  protected void onCreate() {
    if (challengeUid == null) challengeUid = UUID.randomUUID();
    createdAt = OffsetDateTime.now();
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
