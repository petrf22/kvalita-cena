package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.CodeType;
import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.OffFetchStatus;
import cz.kvalitacena.db.entity.OffImageKind;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductCode;
import cz.kvalitacena.db.entity.ProductName;
import cz.kvalitacena.db.entity.ProductUserEdit;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.db.repo.BrandRepository;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.OffProductRepository;
import cz.kvalitacena.db.repo.ProductCodeRepository;
import cz.kvalitacena.db.repo.ProductNameRepository;
import cz.kvalitacena.db.repo.ProductUserEditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Skládá efektivní produkt na DETACHED kopii v pořadí komunitní základ → OFF → osobní patch.
 * Hodnoty z {@code off.*} se tím nikdy nepropíšou do spravované entity ani do {@code core.*}.
 *
 * <p>Název a fotky mají vlastní pravidlo, protože nejsou jednohodnotové, ale jazykové:
 * neskládají se přebíjením vrstev, ale výběrem podle jazyka requestu
 * ({@link ProductNameResolver}, {@link OffImageResolver}). Uvnitř jednoho jazyka platí
 * pořadí patch → komunita → OFF, napříč jazyky se sahá teprve tehdy, když v jazyce klienta
 * nemá název nikdo.
 */
@Service
@RequiredArgsConstructor
public class ProductOverlayService {

  private final ProductUserEditRepository productUserEditRepository;
  private final ProductNameRepository productNameRepository;
  private final ProductCodeRepository productCodeRepository;
  private final OffProductRepository offProductRepository;
  private final BrandRepository brandRepository;
  private final CategoryRepository categoryRepository;
  private final OffNetContentConverter netContentConverter;
  private final ProductNameResolver nameResolver;
  private final OffImageResolver imageResolver;

  /** Jeden produkt — detail, editace a snapshot gramáže při zápisu ceny. */
  public Product applyOverlay(Product product, Long viewerId) {
    if (product == null) return null;
    OffProduct off = offFor(product);
    Map<String, Category> categories = categoryFor(off);
    ProductUserEdit edit = viewerId == null ? null
        : productUserEditRepository.findByProductIdAndUserId(product.getId(), viewerId).orElse(null);
    return merge(product, off, edit, productNameRepository.findByProductId(product.getId()), categories);
  }

  /** Dávka — hledání/seznamy, nejvýš jeden dotaz pro kódy, OFF snapshoty, kategorie a patche. */
  public List<Product> applyOverlay(List<Product> products, Long viewerId) {
    if (products.isEmpty()) return products;
    List<Long> ids = products.stream().map(Product::getId).toList();
    Map<Long, String> gtins = gtinsByProductId(ids);
    Map<String, OffProduct> offByGtin = offProductRepository.findByGtinIn(gtins.values()).stream()
        .filter(p -> p.getFetchStatus() == OffFetchStatus.FOUND)
        .collect(Collectors.toMap(OffProduct::getGtin, Function.identity()));
    Map<String, Category> categories = categoriesBySlug(offByGtin.values());
    Map<Long, ProductUserEdit> edits = viewerId == null ? Map.of() : productUserEditRepository
        .findByProductIdInAndUserId(ids, viewerId).stream()
        .collect(Collectors.toMap(ProductUserEdit::getProductId, Function.identity()));

    Map<Long, List<ProductName>> namesByProduct = productNameRepository.findByProductIdIn(ids).stream()
        .collect(Collectors.groupingBy(ProductName::getProductId));

    return products.stream().map(product -> merge(product, offByGtin.get(gtins.get(product.getId())),
        edits.get(product.getId()), namesByProduct.getOrDefault(product.getId(), List.of()), categories))
        .toList();
  }

  /**
   * Jedno místo, kde vzniká detached kopie — název potřebuje VŠECHNY tři vrstvy najednou
   * (nedá se poskládat postupným přebíjením, viz {@link ProductNameResolver}), takže se
   * nedělí na applyOff/mergeUser jako zbytek polí.
   */
  private Product merge(Product product, OffProduct off, ProductUserEdit edit,
      List<ProductName> communityNames, Map<String, Category> categories) {
    Product.ProductBuilder builder = applyOff(product, off, categories);
    if (edit != null) mergeUser(builder, edit);

    ResolvedProductName effective = nameResolver.effective(product, off, edit, communityNames);
    if (effective != null) builder.name(effective.name()).nameLang(effective.lang());
    return builder.build();
  }

  private OffProduct offFor(Product product) {
    return productCodeRepository.findByProductId(product.getId()).stream()
        .filter(c -> c.getCodeType() == CodeType.GTIN)
        .sorted(java.util.Comparator.comparing(ProductCode::isPrimary).reversed())
        .map(ProductCode::getCode).map(offProductRepository::findById)
        .flatMap(java.util.Optional::stream)
        .filter(p -> p.getFetchStatus() == OffFetchStatus.FOUND).findFirst().orElse(null);
  }

  private Map<Long, String> gtinsByProductId(Collection<Long> productIds) {
    Map<Long, List<ProductCode>> grouped = productCodeRepository.findByProductIdIn(productIds).stream()
        .filter(c -> c.getCodeType() == CodeType.GTIN)
        .collect(Collectors.groupingBy(c -> c.getProduct().getId()));
    return grouped.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().stream()
        .sorted(java.util.Comparator.comparing(ProductCode::isPrimary).reversed())
        .map(ProductCode::getCode).findFirst().orElseThrow()));
  }

  private Map<String, Category> categoryFor(OffProduct off) {
    if (off == null || off.getMappedCategorySlug() == null) return Map.of();
    return categoryRepository.findBySlug(off.getMappedCategorySlug())
        .map(category -> Map.of(category.getSlug(), category)).orElseGet(Map::of);
  }

  private Map<String, Category> categoriesBySlug(Collection<OffProduct> products) {
    List<String> slugs = products.stream().map(OffProduct::getMappedCategorySlug)
        .filter(Objects::nonNull).distinct().toList();
    if (slugs.isEmpty()) return Map.of();
    return categoryRepository.findBySlugIn(slugs).stream()
        .collect(Collectors.toMap(Category::getSlug, Function.identity()));
  }

  private Product.ProductBuilder applyOff(Product product, OffProduct off, Map<String, Category> categories) {
    Product.ProductBuilder builder = product.toBuilder()
        .offBacked(false).externalBrandName(null).offImageFrontUrl(null).offImageFrontSmallUrl(null)
        .offImageLang(null).offImages(List.of());
    if (off == null) return builder;

    // Fotka obalu v jazyce klienta, ne "hlavní" varianta z OFF — u vícejazyčného zboží by
    // jinak česky mluvící uživatel viděl německý obal (docs/lokalizace.md).
    builder.offBacked(true).offImages(imageResolver.all(off));
    imageResolver.preferred(off, OffImageKind.FRONT).ifPresent(front -> builder
        .offImageFrontUrl(front.getUrl())
        .offImageFrontSmallUrl(front.getSmallUrl() != null ? front.getSmallUrl() : front.getUrl())
        .offImageLang(front.getLang()));
    if (off.getBrandName() != null) builder.brand(null).externalBrandName(off.getBrandName());
    // Slug MUSÍ projít testem na null dřív, než se sáhne do mapy — categoryFor() vrací pro
    // nenamapované zboží Map.of() a to na get(null) hází NPE (na rozdíl od HashMapy z dávkové
    // větve). Zboží, jehož kategorie z OFF nesedí na náš strom, je běžný stav, ne chyba.
    Category category = off.getMappedCategorySlug() == null ? null
        : categories.get(off.getMappedCategorySlug());
    if (category != null) builder.category(category);
    OffNetContent content = netContentConverter.convert(off);
    if (content != null) {
      builder.unitBase(content.unitBase()).netContentValue(content.value()).netContentUom(content.uom())
          .netContentBase(content.base()).variableWeight(false);
    }
    return builder;
  }

  /** Pole KROMĚ názvu — ten řeší {@link #merge} přes {@link ProductNameResolver}. */
  private void mergeUser(Product.ProductBuilder builder, ProductUserEdit edit) {
    if (edit.getBrandId() != null) {
      brandRepository.findById(edit.getBrandId()).ifPresent(brand -> builder.brand(brand).externalBrandName(null));
    } else if (edit.getClearedFields().contains("brand")) {
      builder.brand(null).externalBrandName(null);
    }
    if (edit.getCategoryId() != null) categoryRepository.findById(edit.getCategoryId()).ifPresent(builder::category);
    if (edit.getUnitBase() != null) builder.unitBase(UnitBase.valueOf(edit.getUnitBase()));
    if (edit.getNetContentValue() != null) builder.netContentValue(edit.getNetContentValue());
    if (edit.getNetContentUom() != null) builder.netContentUom(NetContentUom.valueOf(edit.getNetContentUom()));
    if (edit.getNetContentBase() != null) builder.netContentBase(edit.getNetContentBase());
    if (edit.getPiecesInPack() != null) builder.piecesInPack(edit.getPiecesInPack());
    if (edit.getVariableWeight() != null) builder.variableWeight(edit.getVariableWeight());
    builder.editedByMe(true);
  }
}
