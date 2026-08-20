package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;

/**
 * Zpětná vazba od uživatele appky (uzavřená beta, docs/nasazeni.md „Než pozvat první lidi") —
 * na rozdíl od {@link RecordFlag} necílí na žádný jiný záznam, jde přímo o appku samotnou.
 * Funguje i anonymně ({@code userId} nullable), protože zrovna nepřihlášený tester narazí na
 * nejcennější hlášení — třeba že se vůbec nedokázal přihlásit.
 *
 * <p><b>Vědomá odchylka od {@link RecordFlag}:</b> tam se {@code user_id} z API nikdy nevrací
 * (docs/soukromi.md) — tady se autor (je-li přihlášený) naopak vrací moderátorovi vždycky,
 * protože bez něj není komu na hlášení odpovědět. Jiná věc, jiné pravidlo, stejně jako dnes
 * {@code authorPublicUid} u {@code FlaggedRecordItem} vs. skrytý {@code record_flag.user_id}
 * (docs/reputace.md, „Moderace").
 *
 * <p>{@code contactEmailEnc} je nepovinný kontakt NAVÍC k účtu (tester nemusí být přihlášený
 * vůbec) — šifrovaný stejným AES-256-GCM jako ostatní textová PII profilu
 * ({@link cz.kvalitacena.security.EmailCipher#encryptValue}).
 */
@Entity
@Table(name = "feedback", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Feedback implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id")
  private Long userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 20)
  private FeedbackCategory category;

  @Column(name = "message", nullable = false, columnDefinition = "TEXT")
  private String message;

  @Column(name = "contact_email_enc")
  private byte[] contactEmailEnc;

  @Enumerated(EnumType.STRING)
  @Column(name = "client_kind", nullable = false, length = 10)
  private ClientKind clientKind;

  @Column(name = "app_version", length = 30)
  private String appVersion;

  @Column(name = "platform_info", length = 200)
  private String platformInfo;

  @Column(name = "locale", length = 10)
  private String locale;

  @Column(name = "country", length = 2)
  private String country;

  @Column(name = "page_ref", length = 200)
  private String pageRef;

  @Column(name = "diagnostics", columnDefinition = "TEXT")
  private String diagnostics;

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  // Vyplní FeedbackService.setHandled — NULL = "čeká na vyřízení", stejný vzor jako
  // RecordFlag.resolvedAt.
  @Column(name = "handled_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime handledAt;

  @Column(name = "handled_by_user_id")
  private Long handledByUserId;

  @Column(name = "handled_note", length = 500)
  private String handledNote;

  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
