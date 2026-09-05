package cz.kvalitacena.db.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

  // Hlavní jazyk záznamu v OFF (pole `lang`) — říká, v jakém jazyce je productName níž.
  // NULL u snapshotů stažených dřív, než se jazyk vůbec sledoval.
  @Column(name = "lang", length = 5)
  private String lang;

  // "Hlavní" varianta názvu z OFF (pole `product_name`). Je to POČÍTANÉ pole — u produktu
  // s lang='en' může vrátit český text — takže se z něj jazyk poznat NEDÁ; slouží jen jako
  // poslední článek fallbacku, když names nemá nic použitelného.
  @Column(name = "product_name", length = 300)
  private String productName;

  // Název po jazycích (`product_name_<lc>`), klíč je dvoupísmenný kód jazyka. OFF vrací jen
  // ty jazyky, o které si klient řekne — které to byly, drží nameLocales níž.
  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(name = "product_name", schema = "off", joinColumns = @JoinColumn(name = "gtin"))
  @MapKeyColumn(name = "lang")
  @Column(name = "name", nullable = false, length = 300)
  @BatchSize(size = 100)
  @Builder.Default
  private Map<String, String> names = new LinkedHashMap<>();

  // Jazyky, které jsme si při posledním fetchi VYŽÁDALI (ne ty, které OFF vrátil) — prázdný
  // seznam znamená snapshot stažený po staru. OpenFoodFactsService podle toho pozná, že po
  // rozšíření appky o další jazyk je snapshot nečerstvý, i když mu jinak nevypršelo TTL.
  @Column(name = "name_locales", nullable = false)
  @Builder.Default
  private List<String> nameLocales = new ArrayList<>();

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

  // Vybrané fotky po druhu a jazyku obalu (`selected_images`). Nadmnožina imageFront*Url výš
  // — ty zůstávají jako fallback pro snapshoty stažené po staru.
  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(name = "product_image", schema = "off", joinColumns = @JoinColumn(name = "gtin"))
  @BatchSize(size = 100)
  @Builder.Default
  private List<OffProductImage> images = new ArrayList<>();

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
