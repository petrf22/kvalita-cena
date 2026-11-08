package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Naskenovaná účtenka — soukromý přehled nákupu vlastníka (docs/soukromi.md, „Účtenky"), nikdy
 * veřejná. Hlavička z workeru je syrový text; {@code storeId} je obchod, který potvrdil
 * uživatel, {@code suggestedStoreId} jen návrh serveru.
 *
 * <p>{@code @DynamicUpdate}: účtenku zapisuje worker, vlastník i retenční job — každý jen svoje
 * sloupce, aby zápis z dřív načtené entity nepřepsal cizí změnu (pojistka k zámku
 * {@code ReceiptRepository.findForUpdateById}).
 */
@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "receipt", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Receipt implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "owner_user_id", nullable = false)
  private Long ownerUserId;

  @Enumerated(EnumType.STRING)
  @Column(name = "processing_status", nullable = false, length = 16)
  @Builder.Default
  private ReceiptProcessingStatus processingStatus = ReceiptProcessingStatus.QUEUED;

  @Enumerated(EnumType.STRING)
  @Column(name = "review_status", nullable = false, length = 12)
  @Builder.Default
  private ReceiptReviewStatus reviewStatus = ReceiptReviewStatus.PENDING;

  @Column(name = "consent_version", nullable = false)
  private Integer consentVersion;

  @Column(name = "archive_consent", nullable = false)
  private boolean archiveConsent;

  /** Klíč idempotence nahrání (id položky fronty v mobilu) — opakovaný upload vrátí tutéž účtenku. */
  @Column(name = "client_upload_id")
  private UUID clientUploadId;

  /** Kdy uživatel účtenku vyfotil (hodiny klienta) — jen nápověda, když worker datum nepřečte. */
  @Column(name = "captured_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime capturedAt;

  @Column(name = "store_name_raw", length = 200)
  private String storeNameRaw;

  @Column(name = "store_address_raw", length = 300)
  private String storeAddressRaw;

  @Column(name = "store_ico_raw", length = 20)
  private String storeIcoRaw;

  /** Datum A čas nákupu — stává se observedAt všech cen potvrzených z účtenky. */
  @Column(name = "purchased_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime purchasedAt;

  @Column(name = "total_amount", precision = 10, scale = 2)
  private BigDecimal totalAmount;

  @Column(name = "currency", length = 3)
  private String currency;

  /** Kontrolní součet workeru — řídí předvýběr řádků ke zveřejnění, nic nezakazuje. */
  @Enumerated(EnumType.STRING)
  @Column(name = "sum_status", nullable = false, length = 10)
  @Builder.Default
  private ReceiptSumStatus sumStatus = ReceiptSumStatus.UNKNOWN;

  /** Součet položek, jak ho spočítal worker (i se zaokrouhlením) — pro upozornění na rozdíl. */
  @Column(name = "computed_total", precision = 10, scale = 2)
  private BigDecimal computedTotal;

  @Column(name = "store_id")
  private Long storeId;

  /** Kdy vlastník obchod naposledy potvrdil — řídí krok 0 návrhu obchodu u dalších účtenek. */
  @Column(name = "store_confirmed_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime storeConfirmedAt;

  /**
   * Vlastník nakoupil s věrnostní kartou obchodu — určuje druh ceny po slevě
   * ({@code ReceiptReviewService.discountKind}); {@code null} = nevyplněno.
   */
  @Column(name = "loyalty_card_used")
  private Boolean loyaltyCardUsed;

  @Column(name = "suggested_store_id")
  private Long suggestedStoreId;

  @Column(name = "processed_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime processedAt;

  @Column(name = "seen_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime seenAt;

  @Column(name = "archived_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime archivedAt;

  @Column(name = "images_deleted_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime imagesDeletedAt;

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  @PrePersist
  void onCreate() {
    createdAt = OffsetDateTime.now();
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
