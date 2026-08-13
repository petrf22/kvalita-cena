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

  // Kanonický, jazykově neutrální klíč ("blue-stork-4271") — na tomhle tvaru visí unique
  // constraint, ne na vyrenderovaném textu (viz HandleGenerator, docs/lokalizace.md).
  @Column(name = "public_handle", nullable = false, unique = true, length = 40)
  private String publicHandle;

  @Column(name = "handle_adjective", length = 24)
  private String handleAdjective;

  @Column(name = "handle_noun", length = 24)
  private String handleNoun;

  @Column(name = "handle_number")
  private Short handleNumber;

  @Column(name = "display_name", length = 40)
  private String displayName;

  // Preference jazyka/země pro ASYNCHRONNÍ výstup (OTP e-mail, později notifikace) —
  // NULL = "uživatel se nevyjádřil", jiný stav než "chce češtinu" (docs/lokalizace.md).
  // Synchronní odpovědi API se řídí Accept-Language, ne tímhle sloupcem.
  @Column(name = "locale", length = 5)
  private String locale;

  @Column(name = "country", length = 2)
  private String country;

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

  // Nedecay-ovaný čítač vlastních cenových záznamů — nutný pro TrustLevelService, protože
  // price_observation.submitter_id se po 180 dnech nuluje (docs/soukromi.md). Inkrementuje
  // ho PriceObservationService.submit(), nikdy se nepočítá zpětně z historie observací.
  @Column(name = "observation_count", nullable = false)
  @Builder.Default
  private int observationCount = 0;

  // NULL = registrace proběhla před zavedením souhlasu, nebo appka konkrétní účet nedopočítala
  // zpětně (docs/soukromi.md). Vyplňuje OtpService.verifyOtp při JIT registraci, ne dřív.
  @Column(name = "terms_accepted_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime termsAcceptedAt;

  @Column(name = "terms_version", length = 20)
  private String termsVersion;

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
