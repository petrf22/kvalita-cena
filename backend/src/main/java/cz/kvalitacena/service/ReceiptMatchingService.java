package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.config.ReceiptProperties;
import cz.kvalitacena.db.entity.CodeType;
import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductCode;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.entity.ProductStoreLabel;
import cz.kvalitacena.db.entity.Receipt;
import cz.kvalitacena.db.entity.ReceiptLine;
import cz.kvalitacena.db.entity.ReceiptLineStatus;
import cz.kvalitacena.db.entity.ReceiptMatchSource;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.ProductCodeRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductStoreLabelRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Kaskáda párování účtenky (docs/rozvoj.md, „Mapování obchodního označení zboží na
 * katalogovou položku"): obchod z hlavičky a zboží pro každý řádek. Kroky 1–2 (kód, známé
 * označení) jsou strojově jisté a dají řádku {@link ReceiptLineStatus#SUGGESTED}, stejně jako
 * EAN známý jen Open Food Facts ({@link ReceiptMatchSource#OFF}, zboží zatím bez záznamu); krok 3a
 * (jednoznačná podobnost názvu) dá taky SUGGESTED, ale se zdrojem
 * {@link ReceiptMatchSource#FUZZY} — klient ho ukáže jako odhad a nezaškrtne předem; krok 3
 * (podobnost) je jinak jen našeptávač {@link #suggestions}; krok 4 (nové zboží) dělá klient.
 * Nic tady nezapisuje cenu — to je výhradně potvrzení uživatele (docs/ai.md, „AI nikdy
 * nerozhoduje").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptMatchingService {

  /** Pod touhle podobností adresy se návrh obchodu nedává, pokud kandidátů je víc. */
  private static final double ADDRESS_SIMILARITY_MIN = 0.4;
  /** Kandidátů pro odhad — víc než dva, protože jedno zboží s víc GTIN přijde víckrát. */
  private static final int FUZZY_CANDIDATES = 6;
  /** Kratší dotaz (po odebrání čísel) je na odhad příliš obecný („Rum", „Bio"). */
  private static final int FUZZY_MIN_LETTERS = 4;

  private final StoreRepository storeRepository;
  private final ProductCodeRepository productCodeRepository;
  private final ProductStoreLabelRepository labelRepository;
  private final ProductRepository productRepository;
  private final ReceiptProperties receiptProperties;
  private final CatalogProperties catalogProperties;
  private final ProductOverlayService productOverlayService;
  private final OpenFoodFactsService offService;
  private final OffNetContentConverter netContentConverter;
  private final CategoryRepository categoryRepository;

  /** Návrh obchodu z hlavičky — jen návrh, potvrdí ho uživatel (updateReceiptHeader). */
  @Transactional(readOnly = true)
  public Optional<Store> suggestStore(Receipt receipt) {
    String ico = normalizeIco(receipt.getStoreIcoRaw());
    String name = blankToNull(receipt.getStoreNameRaw());
    String address = blankToNull(receipt.getStoreAddressRaw());
    if (ico == null && name == null) {
      return Optional.empty();
    }
    List<Store> candidates = storeRepository.findReceiptCandidates(ico, name, address);
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    Store best = candidates.getFirst();
    if (candidates.size() == 1) {
      return Optional.of(best);
    }
    // Víc poboček téhož provozovatele — bez adresy, která jednu z nich jasně vybere, by návrh
    // byl hádání a uživatel by ho snadno přehlédl.
    if (address == null) {
      return Optional.empty();
    }
    Double similarity = storeRepository.addressSimilarity(best.getId(), address);
    return similarity != null && similarity >= ADDRESS_SIMILARITY_MIN ? Optional.of(best) : Optional.empty();
  }

  /**
   * Kroky 1–3a kaskády pro řádky, které ještě nikdo ručně nepotvrdil. Řádky spárované
   * uživatelem ({@code USER}/{@code NEW_PRODUCT}), ignorované nebo s cenou se nemění — po
   * změně obchodu se tak přepočítají jen strojové návrhy.
   *
   * <p>Globální EAN nezávisí na obchodu, páruje se proto i bez něj; vnitroobchodní kód
   * a naučené označení obchod (řetězec) potřebují. EAN, který katalog nezná, ale zná ho Open
   * Food Facts, dá návrh {@link ReceiptMatchSource#OFF} bez zboží — zboží se založí až při
   * potvrzení řádku ({@code ReceiptReviewService.confirmLine}). Odhad podle názvu má před OFF
   * přednost: zboží bez EAN, které v katalogu už je, by jinak dostalo duplicitu.
   */
  public void matchLines(List<ReceiptLine> lines, Store store, Long viewerId) {
    Long chainId = store != null && store.getChain() != null ? store.getChain().getId() : null;
    for (ReceiptLine line : lines) {
      if (line.getObservationId() != null || line.getStatus() == ReceiptLineStatus.IGNORED
          || line.getStatus() == ReceiptLineStatus.MATCHED) {
        continue;
      }
      if (line.getMatchSource() == ReceiptMatchSource.USER || line.getMatchSource() == ReceiptMatchSource.NEW_PRODUCT) {
        continue;
      }
      Optional<Long> productId = byCode(line.getInternalCode(), chainId);
      ReceiptMatchSource source = ReceiptMatchSource.CODE;
      if (productId.isEmpty() && store != null) {
        productId = labelRepository.findExact(line.getLabelRaw(), store.getId(), chainId, viewerId).stream()
            .findFirst().map(ProductStoreLabel::getProductId);
        source = ReceiptMatchSource.LABEL;
      }
      if (productId.isEmpty() && store != null) {
        productId = unambiguousByName(line, store, viewerId);
        source = ReceiptMatchSource.FUZZY;
      }
      if (productId.isPresent()) {
        line.setProductId(productId.get());
        line.setMatchSource(source);
        line.setStatus(ReceiptLineStatus.SUGGESTED);
      } else if (offCandidate(line).isPresent()) {
        line.setProductId(null);
        line.setMatchSource(ReceiptMatchSource.OFF);
        line.setStatus(ReceiptLineStatus.SUGGESTED);
      } else {
        line.setProductId(null);
        line.setMatchSource(null);
        line.setStatus(ReceiptLineStatus.UNMATCHED);
      }
    }
  }

  /**
   * Zboží z Open Food Facts, které jde pro řádek založit bez formuláře: globální EAN, který
   * katalog nezná, OFF ho zná a dodá vše, co {@code OffProductCatalogService.create} jinak
   * chce od uživatele — kategorii namapovanou na katalog appky a gramáž. Název stačí z řádku
   * (čitelný název je vlastní text, ne OFF data). Čemu něco chybí, zůstane nespárované
   * a klient nabídne formulář, který si OFF dotáhne sám.
   *
   * <p>Výpadek OFF ({@code UNAVAILABLE}) není chyba účtenky, jen řádek bez návrhu.
   *
   * <p>Transakce je ZÁMĚRNĚ zapisovatelná: prošlý snapshot obnoví {@code OpenFoodFactsService.lookup}
   * přes {@code repository.save} a v read-only transakci by se obnova tiše zahodila — každé
   * otevření účtenky by pak znovu volalo OFF a pálilo sdílený limit dotazů.
   */
  @Transactional
  public Optional<OffProduct> offCandidate(ReceiptLine line) {
    String code = line.getInternalCode();
    if (!GtinNormalization.isGlobalGtin(code)) {
      return Optional.empty();
    }
    OffLookupResult lookup;
    try {
      lookup = offService.lookup(code.trim());
    } catch (RuntimeException e) {
      log.warn("Účtenka: dotaz do OFF pro řádek {} selhal: {}", line.getId(), e.getMessage());
      return Optional.empty();
    }
    if (lookup.status() != OffLookupStatus.FOUND || lookup.product() == null) {
      return Optional.empty();
    }
    OffProduct off = lookup.product();
    // Názvy jsou lazy kolekce — načíst je tady, ať je volající (potvrzení, náhled pro klienta)
    // může číst i mimo tuhle transakci.
    boolean hasName = !off.getNames().isEmpty() || off.getProductName() != null
        || line.getLabelDisplay() != null || (line.getLabelRaw() != null && !line.getLabelRaw().isBlank());
    boolean hasCategory = off.getMappedCategorySlug() != null
        && categoryRepository.findBySlug(off.getMappedCategorySlug()).isPresent();
    if (!hasName || !hasCategory || netContentConverter.convert(off) == null) {
      return Optional.empty();
    }
    return Optional.of(off);
  }

  /**
   * Krok 3a: zboží, jehož název je čitelnému názvu řádku jednoznačně nejpodobnější — nejlepší
   * kandidát nad {@code auto-match-similarity} a o {@code auto-match-margin} před druhým.
   * Čísla se z dotazu vynechají (gramáž hlídá vodítko zvlášť, v názvu zboží často není).
   *
   * <p>Pojistky proti tiché chybě, protože potvrzený odhad se naučí jako označení a připíše
   * zboží EAN z řádku ({@code ReceiptReviewService.learnCode}):
   * <ul>
   *   <li>DRAFT kandidát odhad nedostane — nepotvrzený název nemá řídit párování;</li>
   *   <li>nese-li řádek vodítko gramáže, zboží ji musí mít a musí sedět po převodu na kg/l;</li>
   *   <li>nese-li řádek EAN (katalog ho nezná, jinak by vyhrál krok 1), zboží s JINÝM EAN
   *       odhad nedostane — to je rozpor, ne jiné balení.</li>
   * </ul>
   */
  private Optional<Long> unambiguousByName(ReceiptLine line, Store store, Long viewerId) {
    String query = fuzzyQuery(line);
    if (query.codePoints().filter(Character::isLetter).count() < FUZZY_MIN_LETTERS) {
      return Optional.empty();
    }
    double minimum = receiptProperties.getAutoMatchSimilarity();
    double margin = receiptProperties.getAutoMatchMargin();
    // Práh dotazu o odstup níž — jinak by druhý kandidát těsně pod prahem nebyl vidět
    // a jednoznačnost by se posuzovala jen proti těm, kdo ho přeskočili.
    List<ProductRepository.ScoredProduct> rows = productRepository.findSimilarByNameScored(query, store.getId(),
        viewerId, Math.max(0, minimum - margin), FUZZY_CANDIDATES);
    Map<Long, ProductRepository.ScoredProduct> best = new LinkedHashMap<>();
    rows.forEach(row -> best.putIfAbsent(row.getId(), row));
    List<ProductRepository.ScoredProduct> candidates = new ArrayList<>(best.values());
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    ProductRepository.ScoredProduct top = candidates.getFirst();
    double second = candidates.size() > 1 ? candidates.get(1).getScore() : 0;
    if (top.getScore() < minimum || top.getScore() - second < margin
        || !ProductStatus.ACTIVE.name().equals(top.getStatus())) {
      return Optional.empty();
    }
    return productRepository.findById(top.getId())
        .map(product -> productOverlayService.applyOverlay(product, viewerId))
        .filter(product -> netContentAgrees(line, product))
        .filter(product -> codeAgrees(line, product))
        .map(Product::getId);
  }

  static String fuzzyQuery(ReceiptLine line) {
    String text = line.getLabelDisplay() != null ? line.getLabelDisplay() : line.getLabelRaw();
    if (text == null) {
      return "";
    }
    return searchableLabel(String.join(" ", java.util.Arrays.stream(text.split("\\s+"))
        .filter(token -> token.chars().noneMatch(Character::isDigit)).toList()));
  }

  static boolean netContentAgrees(ReceiptLine line, Product product) {
    BigDecimal hint = baseAmount(line.getNetContentHintValue(), line.getNetContentHintUom());
    if (hint == null) {
      return true;
    }
    if (product.isVariableWeight()) {
      return false;
    }
    BigDecimal own = baseAmount(product.getNetContentValue(), product.getNetContentUom());
    return own != null && own.compareTo(hint) == 0;
  }

  private boolean codeAgrees(ReceiptLine line, Product product) {
    if (!GtinNormalization.isGlobalGtin(line.getInternalCode())) {
      return true;
    }
    return productCodeRepository.findByProductId(product.getId()).stream()
        .noneMatch(code -> code.getCodeType() == CodeType.GTIN);
  }

  /** Gramáž/objem v kg nebo l, stejně jako NetContentCalculator; jiná jednotka = žádná. */
  private static BigDecimal baseAmount(BigDecimal value, NetContentUom uom) {
    if (value == null || uom == null) {
      return null;
    }
    return switch (uom) {
      case G, ML -> value.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
      case KG, L -> value.setScale(6, RoundingMode.HALF_UP);
      case PCS -> null;
    };
  }

  private Optional<Long> byCode(String code, Long chainId) {
    if (code == null || code.isBlank()) {
      return Optional.empty();
    }
    String trimmed = code.trim();
    if (chainId != null) {
      Optional<Long> internal = productCodeRepository.findStoreInternalProductId(trimmed, chainId);
      if (internal.isPresent()) {
        return internal;
      }
    }
    // Některé účtenky tisknou EAN — ten je globální, platí i bez řetězce.
    if (GtinNormalization.isValidCode(trimmed)) {
      return productCodeRepository.findFirstByCodeAndCodeType(GtinNormalization.toGtin14(trimmed), CodeType.GTIN)
          .map(ProductCode::getProduct).map(Product::getId);
    }
    return Optional.empty();
  }

  /**
   * Našeptávač pro nespárovaný řádek (krok 3): nejdřív zboží s podobným označením v rozsahu
   * obchodu, pak podobnost názvu stejně jako {@code productSuggestions}. Zkratka z účtenky
   * („TUK.ROHLIK") se před hledáním názvu zbaví interpunkce, jinak by se tečka počítala do
   * trigramů. Napsaný dotaz návrhy podle označení vypne — uživatel tím říká, že nesedí,
   * a při plném limitu by jinak výsledky podle dotazu vůbec nedostaly místo.
   */
  @Transactional(readOnly = true)
  public List<Product> suggestions(ReceiptLine line, Store store, String query, Long viewerId, int limit) {
    Map<Long, Product> result = new LinkedHashMap<>();
    Long storeId = store != null ? store.getId() : null;
    Long chainId = store != null && store.getChain() != null ? store.getChain().getId() : null;
    boolean explicitQuery = query != null && !query.isBlank();
    if (store != null && !explicitQuery) {
      List<Long> labelProductIds = labelRepository.findSimilar(line.getLabelRaw(), storeId, chainId,
              receiptProperties.getLabelSimilarity(), limit).stream()
          .map(ProductStoreLabel::getProductId).toList();
      productRepository.findAllById(labelProductIds).forEach(p -> result.putIfAbsent(p.getId(), p));
    }
    String name = explicitQuery ? query.trim()
        : searchableLabel(line.getLabelDisplay() != null ? line.getLabelDisplay() : line.getLabelRaw());
    if (!name.isBlank()) {
      List<Product> byName = productRepository.findSimilarByName(name, storeId, viewerId,
          catalogProperties.getSuggestionSimilarity(), limit);
      byName.forEach(p -> result.putIfAbsent(p.getId(), p));
    }
    return new ArrayList<>(result.values()).subList(0, Math.min(limit, result.size()));
  }

  static String searchableLabel(String label) {
    return label == null ? "" : label.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
  }

  /** IČO z účtenky: bez mezer, bez prefixu DIČ („CZ12345678" → „12345678"). */
  static String normalizeIco(String raw) {
    if (raw == null) {
      return null;
    }
    String digits = raw.replaceAll("[^0-9]", "");
    return digits.length() >= 6 && digits.length() <= 10 ? digits : null;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
