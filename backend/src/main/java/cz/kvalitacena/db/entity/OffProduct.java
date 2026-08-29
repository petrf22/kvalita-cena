package cz.kvalitacena.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Lokální snapshot cizích dat Open Food Facts. Entita schválně neleží v {@code core} a nemá
 * vazbu na {@link Product}; spojení vzniká přes GTIN až v katalogové čtecí vrstvě.
 */
@Entity
@Table(name = "product", schema = "off")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OffProduct {

  @Id
  @Column(name = "gtin", length = 14)
  private String gtin;

  @Enumerated(EnumType.STRING)
  @Column(name = "fetch_status", nullable = false, length = 20)
  private OffFetchStatus fetchStatus;

  @Column(name = "product_name", length = 300)
  private String productName;

  @Column(name = "brand_name", length = 200)
  private String brandName;

  @Column(name = "product_quantity", precision = 12, scale = 3)
  private BigDecimal productQuantity;

  @Column(name = "product_quantity_unit", length = 10)
  private String productQuantityUnit;

  @Column(name = "category_tags", nullable = false)
  @Builder.Default
  private List<String> categoryTags = new ArrayList<>();

  @Column(name = "mapped_category_slug", length = 140)
  private String mappedCategorySlug;

  @Column(name = "image_front_url", length = 1000)
  private String imageFrontUrl;

  @Column(name = "image_front_small_url", length = 1000)
  private String imageFrontSmallUrl;

  // Aditiva (E-čka) konkrétního produktu, tagy jako "en:e330" — zobrazí se jako odkazy na
  // ExternalLinkKind.E_NUMBERS (ProductGraphQlController.externalLinksFor).
  @Column(name = "additives_tags", nullable = false)
  @Builder.Default
  private List<String> additivesTags = new ArrayList<>();

  @Column(name = "source_revision")
  private Long sourceRevision;

  @Column(name = "source_updated_at", columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime sourceUpdatedAt;

  @Column(name = "fetched_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime fetchedAt;
}
