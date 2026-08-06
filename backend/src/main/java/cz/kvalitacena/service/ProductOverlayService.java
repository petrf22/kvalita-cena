package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductUserEdit;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.db.repo.BrandRepository;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.ProductUserEditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Aplikuje uživatelův patch (core.product_user_edit) na DETACHED kopii produktu
 * ({@code Product.toBuilder()}) — entita se NIKDY nepřepisuje uvnitř transakce, protože by to
 * Hibernate propsal zpátky do globálního řádku (CLAUDE.md, "autorizace je predikát v dotazu,
 * ne filtr v resolveru"; docs/datovy-model.md, "Uživatelská vrstva nad globálními daty").
 * Kopie vzniklá {@code toBuilder()} nikdy neprošla {@code EntityManager.find()}, takže ji
 * Hibernate nesleduje a nemá jak zapsat zpátky, i kdyby se to zkusilo.
 */
@Service
@RequiredArgsConstructor
public class ProductOverlayService {

  private final ProductUserEditRepository productUserEditRepository;
  private final BrandRepository brandRepository;
  private final CategoryRepository categoryRepository;

  /** Jeden produkt — detail (product/productByCode). */
  public Product applyOverlay(Product product, Long viewerId) {
    if (product == null || viewerId == null) return product;
    return productUserEditRepository.findByProductIdAndUserId(product.getId(), viewerId)
        .map(edit -> merge(product, edit))
        .orElse(product);
  }

  /** Dávka — hledání/seznamy, jeden dotaz na patche místo N+1. */
  public List<Product> applyOverlay(List<Product> products, Long viewerId) {
    if (viewerId == null || products.isEmpty()) return products;
    List<Long> ids = products.stream().map(Product::getId).toList();
    Map<Long, ProductUserEdit> edits = productUserEditRepository
        .findByProductIdInAndUserId(ids, viewerId).stream()
        .collect(Collectors.toMap(ProductUserEdit::getProductId, Function.identity()));
    if (edits.isEmpty()) return products;
    return products.stream()
        .map(p -> edits.containsKey(p.getId()) ? merge(p, edits.get(p.getId())) : p)
        .toList();
  }

  private Product merge(Product product, ProductUserEdit edit) {
    Product.ProductBuilder builder = product.toBuilder();
    if (edit.getName() != null) builder.name(edit.getName());
    if (edit.getBrandId() != null) {
      brandRepository.findById(edit.getBrandId()).ifPresent(builder::brand);
    } else if (edit.getClearedFields().contains("brand")) {
      builder.brand(null);
    }
    if (edit.getCategoryId() != null) {
      categoryRepository.findById(edit.getCategoryId()).ifPresent(builder::category);
    }
    if (edit.getUnitBase() != null) builder.unitBase(UnitBase.valueOf(edit.getUnitBase()));
    if (edit.getNetContentValue() != null) builder.netContentValue(edit.getNetContentValue());
    if (edit.getNetContentUom() != null) builder.netContentUom(NetContentUom.valueOf(edit.getNetContentUom()));
    if (edit.getNetContentBase() != null) builder.netContentBase(edit.getNetContentBase());
    if (edit.getPiecesInPack() != null) builder.piecesInPack(edit.getPiecesInPack());
    if (edit.getVariableWeight() != null) builder.variableWeight(edit.getVariableWeight());
    builder.editedByMe(true);
    return builder.build();
  }
}
