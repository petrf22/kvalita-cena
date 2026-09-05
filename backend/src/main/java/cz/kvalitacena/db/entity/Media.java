package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;

/**
 * Metadata jedné fotky zboží nebo provozovny — binární obsah leží MIMO databázi
 * ({@link cz.kvalitacena.service.MediaStorage}, {@code app.media.root}), tady je jen
 * {@code storageKey} a rozměry (docs/datovy-model.md). {@code recordType}/{@code recordId} je
 * stejný polymorfní vzor bez FK jako u {@link RecordFlag} — PRODUCT i STORE sdílejí tabulku,
 * proto tu není {@code @ManyToOne} na konkrétní entitu.
 *
 * <p>{@code uploadedByUserId} je {@code ON DELETE CASCADE} — fotka bez vlastníka nemá po
 * smazání účtu veřejný zájem, který by zdůvodnil přežití (stejná úvaha jako u
 * {@code core.product_user_edit}, docs/soukromi.md).
 */
@Entity
@Table(name = "media", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Media implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(name = "record_type", nullable = false, length = 10)
  private RecordType recordType;

  @Column(name = "record_id", nullable = false)
  private Long recordId;

  @Column(name = "storage_key", nullable = false, length = 200)
  private String storageKey;

  @Column(name = "uploaded_by_user_id", nullable = false)
  private Long uploadedByUserId;

  @Column(name = "content_type", nullable = false, length = 30)
  private String contentType;

  @Column(name = "width", nullable = false)
  private int width;

  @Column(name = "height", nullable = false)
  private int height;

  @Column(name = "byte_size", nullable = false)
  private int byteSize;

  @Column(name = "sha256", nullable = false)
  private byte[] sha256;

  @Column(name = "caption", length = 200)
  private String caption;

  // Nezávislá osa od recordType (ten říká čí je fotka, tohle co na ní je) — docs/datovy-model.md.
  @Enumerated(EnumType.STRING)
  @Column(name = "photo_kind", nullable = false, length = 10)
  @Builder.Default
  private PhotoKind photoKind = PhotoKind.OTHER;

  // Jazyk obalu/etikety na fotce; NULL = neurčeno (fotky provozoven, avatary a všechno
  // nahrané dřív, než appka jazyk sledovala). U PhotoKind.LABEL je to podstata věci — je to
  // fotka TEXTU složení, takže bez jazyka nejde vybrat ta, které čtenář rozumí, a budoucí
  // čtení složení (docs/ai.md) by nevědělo, co čte.
  @Column(name = "lang", length = 5)
  private String lang;

  @Column(name = "sort_order", nullable = false)
  @Builder.Default
  private int sortOrder = 0;

  @Column(name = "license", nullable = false, length = 20)
  @Builder.Default
  private String license = "CC-BY-SA-4.0";

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  // Skryto po nahlášení (core.record_flag, RecordType.PHOTO) — vidí dál jen autor.
  @Column(name = "hidden_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime hiddenAt;

  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
  }

  @Override
  public boolean isNew() {
    return id == null;
  }

  public boolean isHidden() {
    return hiddenAt != null;
  }
}
