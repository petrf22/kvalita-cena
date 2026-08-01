package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "product", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "brand_id", foreignKey = @ForeignKey(name = "fk_product_brand"))
  private Brand brand;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "category_id", nullable = false, foreignKey = @ForeignKey(name = "fk_product_category"))
  private Category category;

  @Enumerated(EnumType.STRING)
  @Column(name = "unit_base", nullable = false, length = 10)
  private UnitBase unitBase;

  @Column(name = "net_content_value", precision = 12, scale = 3)
  private BigDecimal netContentValue;

  @Enumerated(EnumType.STRING)
  @Column(name = "net_content_uom", length = 5)
  private NetContentUom netContentUom;

  // Vždy v základní jednotce (kg / l / ks) — z ní se počítá jednotková cena (docs/datovy-model.md).
  @Column(name = "net_content_base", nullable = false, precision = 12, scale = 6)
  private BigDecimal netContentBase;

  @Column(name = "pieces_in_pack")
  private Integer piecesInPack;

  @Column(name = "is_variable_weight", nullable = false)
  @Builder.Default
  private boolean variableWeight = false;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  @Builder.Default
  private ProductStatus status = ProductStatus.ACTIVE;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "merged_into_id", foreignKey = @ForeignKey(name = "fk_product_merged_into"))
  private Product mergedInto;

  @Enumerated(EnumType.STRING)
  @Column(name = "data_origin", nullable = false, length = 20)
  @Builder.Default
  private DataOrigin dataOrigin = DataOrigin.OWN;

  @Column(name = "created_by_user_id")
  private Long createdByUserId;

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime updatedAt;

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
}
