package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Vytěžená účtenka — NÁVRH cen k potvrzení, ne cena sama (docs/ai.md, „AI nikdy nerozhoduje").
 * Leží ve schématu {@code ai} mimo {@code core}, aby čistý export vlastních dat
 * ({@code pg_dump --schema=core --schema=agg}) neobsahoval strojové odhady.
 *
 * <p>Z patičky účtenky je tady jen datum nákupu a celková částka. Číslo karty, SEQ ID,
 * autorizační kód, čísla pokladny a věrnostní údaje zahazuje už klientský parser
 * ({@code tools/uctenky}) — na server se nikdy nedostanou, viz docs/soukromi.md, „Účtenka".
 *
 * <p>{@code chainId}/{@code storeId} jsou provozovna DOHLEDANÁ podle hlavičky, ne automaticky
 * založená: zakládání obchodu má vlastní limity a práh potvrzení (docs/reputace.md).
 */
@Entity
@Table(name = "receipt", schema = "ai")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Receipt implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "uploaded_by_user_id", nullable = false)
  private Long uploadedByUserId;

  /** Otisk normalizovaného dokumentu — tentýž soubor nahraný dvakrát je jedna účtenka. */
  @Column(name = "payload_sha256", nullable = false)
  private byte[] payloadSha256;

  @Column(name = "schema_version", nullable = false)
  private int schemaVersion;

  @Column(name = "profile", nullable = false, length = 40)
  private String profile;

  @Column(name = "ocr_model", length = 100)
  private String ocrModel;

  /** Otisk zdrojového obrázku. Obrázek sám na serveru ve fázi lokálního OCR není. */
  @Column(name = "image_sha256", length = 64)
  private String imageSha256;

  @Column(name = "raw_merchant_name", length = 200)
  private String rawMerchantName;

  @Column(name = "raw_street", length = 200)
  private String rawStreet;

  @Column(name = "raw_city", length = 200)
  private String rawCity;

  @Column(name = "chain_id")
  private Long chainId;

  @Column(name = "store_id")
  private Long storeId;

  /** Kdy uživatel ceny viděl — vstupuje do observací jako {@code observed_at}. */
  @Column(name = "purchased_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime purchasedAt;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "printed_total", precision = 12, scale = 2)
  private BigDecimal printedTotal;

  @Column(name = "computed_total", nullable = false, precision = 12, scale = 2)
  private BigDecimal computedTotal;

  /**
   * Součet položek a slev sedí na vytištěnou částku. Na reálných účtenkách vychází na haléř,
   * takže rozsypané OCR se pozná spolehlivě — účtenka, která nesedí, se nezapisuje do cen.
   */
  @Column(name = "total_matches", nullable = false)
  @Builder.Default
  private boolean totalMatches = false;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 12)
  @Builder.Default
  private ReceiptStatus status = ReceiptStatus.IMPORTED;

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  @Column(name = "confirmed_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime confirmedAt;

  @PrePersist
  void onCreate() {
    createdAt = OffsetDateTime.now();
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
