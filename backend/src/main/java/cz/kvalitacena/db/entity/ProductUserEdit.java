package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Uživatelská úprava zboží — vedlejší "patch" tabulka nad core.product, jen změněná pole
 * (docs/datovy-model.md, "Uživatelská vrstva nad globálními daty"). {@code NULL} u editovatelného
 * pole znamená "nezměněno"; {@code clearedFields} odlišuje "nezměněno" od "uživatel volitelné
 * pole vymazal" (např. odebrání chybného IČO by jinak nešlo vyjádřit).
 *
 * <p>{@code productId}/{@code userId} jsou schválně skalární, ne {@code @ManyToOne} — stejný
 * důvod jako u {@link ProductReview}: entita se čte a zapisuje jen přímo přes repozitář,
 * překryv nad hledáním/detailem se skládá v SQL (CLAUDE.md, "autorizace je predikát v dotazu,
 * ne filtr v resolveru"), nikdy načtením a přemapováním této entity.
 */
@Entity
@Table(name = "product_user_edit", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(ProductUserEdit.Id.class)
public class ProductUserEdit {

  @jakarta.persistence.Id
  @Column(name = "product_id")
  private Long productId;

  @jakarta.persistence.Id
  @Column(name = "user_id")
  private Long userId;

  @Column(name = "name", length = 200)
  private String name;

  @Column(name = "brand_id")
  private Long brandId;

  @Column(name = "category_id")
  private Long categoryId;

  @Column(name = "unit_base", length = 10)
  private String unitBase;

  @Column(name = "net_content_value", precision = 12, scale = 3)
  private BigDecimal netContentValue;

  @Column(name = "net_content_uom", length = 5)
  private String netContentUom;

  @Column(name = "net_content_base", precision = 12, scale = 6)
  private BigDecimal netContentBase;

  @Column(name = "pieces_in_pack")
  private Integer piecesInPack;

  @Column(name = "is_variable_weight")
  private Boolean variableWeight;

  @Column(name = "cleared_fields")
  @Builder.Default
  private List<String> clearedFields = new ArrayList<>();

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime updatedAt;

  // Vyplňuje budoucí konsolidační job (zpracováno ≠ uznáno za globální) — dokud neběží,
  // zůstává NULL a řádek čeká ve frontě idx_product_user_edit_unprocessed.
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
    private Long productId;
    private Long userId;
  }
}
