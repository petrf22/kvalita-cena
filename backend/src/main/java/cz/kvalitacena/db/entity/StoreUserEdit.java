package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Uživatelská úprava provozovny — stejný vzor jako {@link ProductUserEdit}, jen nad core.store.
 * {@code lat}/{@code lon} v patchi smí ovlivnit JEN zobrazenou hodnotu — filtr vzdálenosti
 * v {@code nearbyStores} musí dál pracovat s globálními souřadnicemi (idx_store_geo), jinak
 * by se rozjelo od GiST indexu (viz backend etapa 2, StoreRepository).
 *
 * <p>{@code country} tu záměrně NENÍ — na rozdíl od zbytku téhle entity má tvrdý dopad na měnu
 * zápisu ({@link cz.kvalitacena.service.CurrencyResolver#forStore}) a validaci IČO/NIP pro
 * VŠECHNY uživatele, ne jen na to, jak provozovnu vidí autor patche. {@code updateStore}
 * (viz {@code CatalogEditService}) proto zemi zapisuje přímo do globálního {@code core.store},
 * gatováno {@code TrustLevelService.isTrusted} (docs/lokalizace.md, "Country selector v UI").
 */
@Entity
@Table(name = "store_user_edit", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(StoreUserEdit.Id.class)
public class StoreUserEdit {

  @jakarta.persistence.Id
  @Column(name = "store_id")
  private Long storeId;

  @jakarta.persistence.Id
  @Column(name = "user_id")
  private Long userId;

  @Column(name = "name", length = 160)
  private String name;

  @Column(name = "chain_id")
  private Long chainId;

  @Column(name = "street", length = 160)
  private String street;

  @Column(name = "city", length = 120)
  private String city;

  @Column(name = "postal_code", length = 10)
  private String postalCode;

  @Column(name = "ico", length = 8)
  private String ico;

  @Column(name = "lat", precision = 9, scale = 6)
  private BigDecimal lat;

  @Column(name = "lon", precision = 9, scale = 6)
  private BigDecimal lon;

  @Column(name = "geo_source", length = 10)
  private String geoSource;

  @Column(name = "osm_ref", length = 32)
  private String osmRef;

  @Column(name = "cleared_fields")
  @Builder.Default
  private List<String> clearedFields = new ArrayList<>();

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime updatedAt;

  @Column(name = "processed_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime processedAt;

  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
    updatedAt = createdAt;
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = OffsetDateTime.now();
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Id implements Serializable {
    private Long storeId;
    private Long userId;
  }
}
