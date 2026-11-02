package cz.kvalitacena.controller;

import cz.kvalitacena.config.ExternalLinkProperties;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.entity.PhotoKind;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.service.Messages;
import cz.kvalitacena.service.OffImageResolver;
import cz.kvalitacena.service.OffNetContent;
import cz.kvalitacena.service.OffNetContentConverter;
import cz.kvalitacena.service.ProductNameResolver;
import cz.kvalitacena.service.ResolvedProductName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Náhled zboží z Open Food Facts pro klienta ({@code ExternalProductCandidate}) — sdílí ho
 * {@code productLookupByCode} (formulář po skenu) a OFF návrh řádku účtenky
 * ({@code ReceiptLine.offCandidate}), ať oba ukazují totéž a se stejnou atribucí.
 */
@Component
@RequiredArgsConstructor
public class ExternalProductCandidateFactory {

  private final CategoryRepository categoryRepository;
  private final OffNetContentConverter offNetContentConverter;
  private final ProductNameResolver productNameResolver;
  private final OffImageResolver offImageResolver;
  private final ExternalLinkProperties externalLinkProperties;
  private final Messages messages;

  public ExternalProductCandidate fromOff(OffProduct off) {
    Category category = off.getMappedCategorySlug() == null ? null
        : categoryRepository.findBySlug(off.getMappedCategorySlug()).orElse(null);
    OffNetContent content = offNetContentConverter.convert(off);
    String attribution = messages.get("attribution.off");
    // Název i fotka se vybírají podle jazyka klienta, ne podle "hlavní" varianty z OFF —
    // jinak formulář nabídne německou Magnesii česky mluvícímu uživateli (docs/lokalizace.md).
    ResolvedProductName offName = productNameResolver.effectiveOffName(off);
    List<ExternalProductImage> images = images(off, attribution);
    String ean = stripLeadingZeros(off.getGtin());
    return new ExternalProductCandidate(
        ean,
        offName == null ? null : offName.name(), offName == null ? null : offName.lang(),
        productNameResolver.offNames(off), off.getBrandName(), category,
        content == null ? null : content.unitBase(), content == null ? null : content.value(),
        content == null ? null : content.uom(),
        images.stream().filter(image -> image.kind() == PhotoKind.ITEM).findFirst().orElse(null),
        images,
        externalLinkProperties.getOpenFoodFacts().getProductUrlTemplate().replace("{barcode}", ean),
        attribution);
  }

  /** Fotky OFF snapshotu s atribucí, jazyk requestu první ({@link OffImageResolver#all}). */
  public List<ExternalProductImage> images(OffProduct off, String attribution) {
    return offImageResolver.all(off).stream()
        .map(image -> image(image.getUrl(), image.getSmallUrl(), image.getKind().toPhotoKind(), image.getLang(),
            attribution))
        .filter(Objects::nonNull).toList();
  }

  /** Fotka OFF pro API; bez URL žádná (`null`). */
  public ExternalProductImage image(String url, String thumbnailUrl, PhotoKind kind, String lang,
      String attribution) {
    String effectiveUrl = url != null ? url : thumbnailUrl;
    if (effectiveUrl == null) return null;
    return new ExternalProductImage(effectiveUrl, thumbnailUrl != null ? thumbnailUrl : effectiveUrl,
        kind, lang, attribution);
  }

  /** Opak normalizace — GTIN-14 zpět na EAN bez vedoucích nul, jak ho zná Open Food Facts. */
  static String stripLeadingZeros(String gtin) {
    String stripped = gtin.replaceFirst("^0+(?!$)", "");
    return stripped.isEmpty() ? gtin : stripped;
  }
}
