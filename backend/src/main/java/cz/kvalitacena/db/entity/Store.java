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
@Builder
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

  @Column(name = "lat", nullable = false, precision = 9, scale = 6)
  private BigDecimal lat;

  @Column(name = "lon", nullable = false, precision = 9, scale = 6)
  private BigDecimal lon;

  @Enumerated(EnumType.STRING)
  @Column(name = "geo_source", nullable = false, length = 10)
  @Builder.Default
  private GeoSource geoSource = GeoSource.COMMUNITY;

  @Column(name = "osm_ref", length = 32)
  private String osmRef;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  @Builder.Default
  private StoreStatus status = StoreStatus.ACTIVE;

  @Column(name = "created_by_user_id")
  private Long createdByUserId;

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
