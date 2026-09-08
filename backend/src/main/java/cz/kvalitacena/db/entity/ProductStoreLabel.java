package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;

/**
 * Most mezi označením zboží na účtence ({@code ROHLIK TUZ 43G}) a katalogovou položkou —
 * docs/rozvoj.md, „Mapování obchodního označení zboží na katalogovou položku".
 *
 * <p>Není to {@link ProductAlias} s jiným jménem: alias je globální pro produkt, míří do
 * veřejného našeptávače a jeho unikátnost je {@code (product_id, name)}. Tady je směr OPAČNÝ —
 * párování jde označení → zboží, takže jedno označení smí v jednom rozsahu ukazovat nejvýš na
 * jednu položku, a interní zkratky jednoho řetězce do našeptávače nepatří.
 *
 * <p>Rozsah je právě jeden z {@code chainId}/{@code storeId}, vzorem {@code chk_product_catalog_scope}.
 */
@Entity
@Table(name = "product_store_label", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductStoreLabel implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** FK na položku, ne na kód — mapování musí přežít {@code mergeProducts}. */
  @Column(name = "product_id", nullable = false)
  private Long productId;

  @Column(name = "label", nullable = false, length = 200)
  private String label;

  @Column(name = "chain_id")
  private Long chainId;

  @Column(name = "store_id")
  private Long storeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 10)
  @Builder.Default
  private ProductStoreLabelStatus status = ProductStoreLabelStatus.PENDING;

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  @Column(name = "activated_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime activatedAt;

  /** Bez téhle značky nejde poznat mrtvé mapování (řetězec zboží přejmenoval) od živého. */
  @Column(name = "last_seen_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime lastSeenAt;

  @PrePersist
  void onCreate() {
    createdAt = OffsetDateTime.now();
    lastSeenAt = createdAt;
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
