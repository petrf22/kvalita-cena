package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Souřadnice PROVOZOVNY jsou veřejný fakt, ne osobní údaj (docs/soukromi.md) — souřadnice
 * UŽIVATELE se v aplikaci neukládají nikde, ani tady, ani jinde.
 */
@Entity
@Table(name = "store", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Store implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "chain_id", foreignKey = @ForeignKey(name = "fk_store_chain"))
  private RetailChain chain;

  @Column(name = "name", nullable = false, length = 160)
  private String name;

  @Column(name = "street", length = 160)
  private String street;

  @Column(name = "city", nullable = false, length = 120)
  private String city;

  @Column(name = "postal_code", length = 10)
  private String postalCode;

  @Column(name = "country", nullable = false, length = 2)
  @Builder.Default
  private String country = "CZ";

  // Nullable — provozovna zadaná doma (bez GPS, zpětný zápis) souřadnice mít nemusí, dokud
  // je nedoplní geokodér nebo někdo, kdo u obchodu fyzicky stojí (docs/datovy-model.md).
  @Column(name = "lat", precision = 9, scale = 6)
  private BigDecimal lat;

  @Column(name = "lon", precision = 9, scale = 6)
  private BigDecimal lon;

  @Enumerated(EnumType.STRING)
  @Column(name = "geo_source", nullable = false, length = 10)
  @Builder.Default
  private GeoSource geoSource = GeoSource.COMMUNITY;

  @Column(name = "osm_ref", length = 32)
  private String osmRef;

  // Volitelný identifikátor provozovatele (podniková prodejna družstva, OSVČ) — 8 číslic,
  // ověřitelné v ARES (docs/soukromi.md — veřejný rejstřík, ne osobní údaj).
  @Column(name = "ico", length = 8)
  private String ico;

  @Column(name = "ico_verified_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime icoVerifiedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  @Builder.Default
  private StoreStatus status = StoreStatus.ACTIVE;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "merged_into_id", foreignKey = @ForeignKey(name = "fk_store_merged_into"))
  private Store mergedInto;

  @Column(name = "created_by_user_id")
  private Long createdByUserId;

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime updatedAt;

  // Uživatelská vrstva nad globálními daty (docs/datovy-model.md) — job zatím neběží, takže
  // je v etapě 1 vždy NULL a klient vše zobrazuje jako "neověřeno".
  @Column(name = "verified_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime verifiedAt;

  @Column(name = "processed_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime processedAt;

  // Skryto po nahlášení (core.record_flag) — vidí jen autor, čeká na přezkum.
  @Column(name = "hidden_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime hiddenAt;

  // Nastavuje StoreOverlayService PO načtení, na detached kopii (toBuilder()) — stejné
  // pravidlo jako Product.editedByMe.
  @Transient
  @Builder.Default
  private boolean editedByMe = false;

  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
    updatedAt = createdAt;
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = OffsetDateTime.now();
  }

  @Override
  public boolean isNew() {
    return id == null;
  }

  /** Job zatím neběží (etapa 2/3) — dokud je verifiedAt NULL, klient zobrazí "neověřeno". */
  public boolean isVerified() {
    return verifiedAt != null;
  }

  /** PENDING vidí jen autor (docs/reputace.md, práh důvěry) — klient tím umí vysvětlit, proč. */
  public boolean isPendingConfirmation() {
    return status == StoreStatus.PENDING;
  }
}
