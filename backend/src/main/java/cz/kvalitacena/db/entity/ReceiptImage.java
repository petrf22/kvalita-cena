package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

/** Jedna část obrázku účtenky v pořadí {@code seq} — soubor v MediaStorage pod receipts/, ne v core.media. */
@Entity
@Table(name = "receipt_image", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiptImage implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "receipt_id", nullable = false)
  private Long receiptId;

  @Column(name = "seq", nullable = false)
  private Short seq;

  @Column(name = "storage_key", nullable = false, length = 100)
  private String storageKey;

  @Column(name = "width", nullable = false)
  private Integer width;

  @Column(name = "height", nullable = false)
  private Integer height;

  @Column(name = "bytes", nullable = false)
  private Integer bytes;

  @Column(name = "sha256", nullable = false)
  private byte[] sha256;

  @Override
  public boolean isNew() {
    return id == null;
  }
}
