package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Profil přihlášeného uživatele (auth.user_profile) — 1:1 s {@link AppUser}, ale ve VLASTNÍ
 * tabulce, aby {@code app_user} zůstal "identitou bez osobních údajů" a smazání profilu bylo
 * jeden {@code DELETE} (docs/soukromi.md). Textová PII ({@code firstNameEnc}/{@code lastNameEnc}/
 * {@code phoneEnc}/{@code contactEmailEnc}) je šifrovaná stejným AES-256-GCM jako
 * {@code app_user.email_enc} (viz {@link cz.kvalitacena.security.EmailCipher}), {@code null} =
 * uživatel pole nevyplnil. {@code avatarMediaId} ukazuje do {@code core.media} bez FK (schémata
 * {@code auth}/{@code core} se mezi sebou nekříží cizím klíčem nikde jinde v projektu) —
 * samotný avatar (na rozdíl od textových polí) šifrovaný NENÍ.
 *
 * <p>{@code userId} je primární klíč PŘEVZATÝ z {@code app_user.id}, ne generovaný — proto
 * entita neimplementuje {@link org.springframework.data.domain.Persistable}: Hibernate merge()
 * nad neznámým manuálně přiřazeným id se chová jako insert, stejný vzor jde použít i pro
 * {@code save()} nově vytvořeného profilu.
 */
@Entity
@Table(name = "user_profile", schema = "auth")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfile {

  @Id
  @Column(name = "user_id")
  private Long userId;

  @Column(name = "first_name_enc")
  private byte[] firstNameEnc;

  @Column(name = "last_name_enc")
  private byte[] lastNameEnc;

  @Column(name = "phone_enc")
  private byte[] phoneEnc;

  @Column(name = "contact_email_enc")
  private byte[] contactEmailEnc;

  @Enumerated(EnumType.STRING)
  @Column(name = "visibility", nullable = false, length = 10)
  @Builder.Default
  private ProfileVisibility visibility = ProfileVisibility.ANONYMOUS;

  @Column(name = "avatar_media_id")
  private Long avatarMediaId;

  @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime updatedAt;

  @PrePersist
  @PreUpdate
  protected void onSave() {
    updatedAt = OffsetDateTime.now();
  }
}
