package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Identita bez osobních údajů — viz docs/soukromi.md. Žádné jméno, adresa ani telefon;
 * veřejně je vidět jen {@code publicHandle} (nebo volitelná {@code displayName}), nikdy
 * {@code id} ani e-mail.
 */
@Entity
@Table(name = "app_user", schema = "auth")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // Používá se v API i jako JWT sub — nikdy DB id, aby nešlo počítat/hádat uživatele.
  @Column(name = "public_uid", nullable = false, unique = true, columnDefinition = "UUID")
  private UUID publicUid;

  // HMAC-SHA256(pepper, normalizovaný e-mail) — jen k vyhledání účtu, ne k jeho obnovení.
  @Column(name = "email_hash", nullable = false, unique = true)
  private byte[] emailHash;

  // AES-256-GCM(e-mail), klíč mimo DB — nutné pro odeslání OTP a notifikací.
  @Column(name = "email_enc", nullable = false)
  private byte[] emailEnc;

  @Column(name = "email_domain", length = 64)
  private String emailDomain;

  @Column(name = "public_handle", nullable = false, unique = true, length = 40)
  private String publicHandle;

  @Column(name = "display_name", length = 40)
  private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private AppUserStatus status;

  // Inkrement zneplatní všechny access tokeny bez nutnosti revokačního seznamu.
  @Column(name = "token_version", nullable = false)
  @Builder.Default
  private int tokenVersion = 0;

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  @Column(name = "last_login_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime lastLoginAt;

  @PrePersist
  protected void onCreate() {
    if (publicUid == null) publicUid = UUID.randomUUID();
    if (status == null) status = AppUserStatus.ACTIVE;
    createdAt = OffsetDateTime.now();
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
