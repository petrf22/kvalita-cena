package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.entity.QuantityUnit;
import cz.kvalitacena.db.entity.ReceiptLine;
import cz.kvalitacena.db.entity.ReceiptLineHint;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ReceiptLineHintRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Předvyplnění formuláře „Založit nové zboží" z řádku účtenky (docs/ai.md, „Návrh kategorie
 * a jednotky"): název, kód, kategorie a gramáž/váhové zboží. Zdroje v pořadí, první vyhrává —
 * deterministické před modelem:
 * <ol>
 *   <li>řádek sám — váhové zboží ({@code KG}/{@code L}) a vodítko gramáže z tisku,</li>
 *   <li>katalog — kategorie, na které se shodnou nejpodobnější aktivní zboží,</li>
 *   <li>lokální model ({@code ai.receipt_line_hint}) — kategorie a jen rozměr gramáže.</li>
 * </ol>
 * Open Food Facts tu záměrně není: formulář si OFF dotáhne sám podle kódu a jeho hodnoty
 * musí zůstat rozpoznatelné jako OFF (ODbL, {@code netContentForOffSubmit}). Výsledek je jen
 * návrh, uživatel ho vidí a potvrzuje — nic tady zboží nezakládá.
 */
@Service
@RequiredArgsConstructor
public class NewProductDefaultsService {

  /** Kolik nejpodobnějších zboží se ptá na kategorii. */
  static final int CATALOG_NEIGHBOURS = 5;
  /** Kolik z nich se musí na kategorii shodnout. */
  static final int CATALOG_MIN_AGREEING = 2;

  private final ProductRepository productRepository;
  private final CategoryRepository categoryRepository;
  private final ReceiptLineHintRepository hintRepository;
  private final CatalogProperties catalogProperties;

  public enum Source { RECEIPT, CATALOG, AI }

  public record Defaults(String name, String code, Category category, Source categorySource,
      BigDecimal netContentValue, NetContentUom netContentUom, boolean isVariableWeight, Source netContentSource) {
  }

  @Transactional(readOnly = true)
  public Defaults forLine(ReceiptLine line, Long storeId, Long viewerId) {
    String name = line.getLabelDisplay() != null ? line.getLabelDisplay() : line.getLabelRaw();
    String code = GtinNormalization.isGlobalGtin(line.getInternalCode()) ? line.getInternalCode().trim() : null;
    ReceiptLineHint hint = hintRepository.findById(line.getId()).orElse(null);

    boolean variableWeight = line.getQuantityUnit() == QuantityUnit.KG || line.getQuantityUnit() == QuantityUnit.L;
    BigDecimal value = null;
    NetContentUom uom = null;
    Source netContentSource = null;
    if (variableWeight) {
      netContentSource = Source.RECEIPT;
    } else if (line.getNetContentHintUom() != null && line.getNetContentHintValue() != null) {
      value = line.getNetContentHintValue();
      uom = line.getNetContentHintUom();
      netContentSource = Source.RECEIPT;
    } else if (hint != null && hint.getNetContentUom() != null) {
      uom = hint.getNetContentUom();
      netContentSource = Source.AI;
    }

    Category category = catalogCategory(line, storeId, viewerId);
    Source categorySource = category != null ? Source.CATALOG : null;
    if (category == null && hint != null && hint.getCategoryId() != null) {
      category = categoryRepository.findById(hint.getCategoryId()).orElse(null);
      categorySource = category != null ? Source.AI : null;
    }
    return new Defaults(name, code, category, categorySource, value, uom, variableWeight, netContentSource);
  }

  /**
   * Kategorie, na které se shodne aspoň {@link #CATALOG_MIN_AGREEING} z
   * {@link #CATALOG_NEIGHBOURS} nejpodobnějších aktivních zboží, a zároveň je jich v ní víc
   * než v kterékoli jiné. Remíza nebo rozptyl = žádný návrh, jen hádání.
   */
  Category catalogCategory(ReceiptLine line, Long storeId, Long viewerId) {
    String query = ReceiptMatchingService.fuzzyQuery(line);
    if (query.codePoints().filter(Character::isLetter).count() < 3) {
      return null;
    }
    List<ProductRepository.ScoredProduct> rows = productRepository.findSimilarByNameScored(query, storeId, viewerId,
        catalogProperties.getSuggestionSimilarity(), CATALOG_NEIGHBOURS * 2);
    List<Long> ids = rows.stream()
        .filter(row -> ProductStatus.ACTIVE.name().equals(row.getStatus()))
        .map(ProductRepository.ScoredProduct::getId).distinct().limit(CATALOG_NEIGHBOURS).toList();
    if (ids.size() < CATALOG_MIN_AGREEING) {
      return null;
    }
    Map<Long, Product> byId = productRepository.findAllById(ids).stream()
        .collect(Collectors.toMap(Product::getId, Function.identity()));
    Map<Long, Long> counts = ids.stream().map(byId::get).filter(Objects::nonNull)
        .map(Product::getCategory).filter(Objects::nonNull)
        .collect(Collectors.groupingBy(Category::getId, LinkedHashMap::new, Collectors.counting()));
    return pickMajority(counts).flatMap(categoryRepository::findById).orElse(null);
  }

  static java.util.Optional<Long> pickMajority(Map<Long, Long> counts) {
    List<Map.Entry<Long, Long>> sorted = counts.entrySet().stream()
        .sorted(Map.Entry.<Long, Long>comparingByValue().reversed()).toList();
    if (sorted.isEmpty() || sorted.getFirst().getValue() < CATALOG_MIN_AGREEING) {
      return java.util.Optional.empty();
    }
    if (sorted.size() > 1 && sorted.get(1).getValue().equals(sorted.getFirst().getValue())) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(sorted.getFirst().getKey());
  }
}
