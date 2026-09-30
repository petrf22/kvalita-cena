package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.config.ReceiptProperties;
import cz.kvalitacena.db.entity.CodeType;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductCode;
import cz.kvalitacena.db.entity.ProductStoreLabel;
import cz.kvalitacena.db.entity.Receipt;
import cz.kvalitacena.db.entity.ReceiptLine;
import cz.kvalitacena.db.entity.ReceiptLineStatus;
import cz.kvalitacena.db.entity.ReceiptMatchSource;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.ProductCodeRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductStoreLabelRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Kaskáda párování účtenky (docs/rozvoj.md, „Mapování obchodního označení zboží na
 * katalogovou položku"): obchod z hlavičky a zboží pro každý řádek. Kroky 1–2 (kód, známé
 * označení) jsou strojově jisté a dají řádku {@link ReceiptLineStatus#SUGGESTED}; krok 3
 * (podobnost) je jen našeptávač {@link #suggestions}; krok 4 (nové zboží) dělá klient.
 * Nic tady nezapisuje cenu — to je výhradně potvrzení uživatele (docs/ai.md, „AI nikdy
 * nerozhoduje").
 */
@Service
@RequiredArgsConstructor
public class ReceiptMatchingService {

  /** Pod touhle podobností adresy se návrh obchodu nedává, pokud kandidátů je víc. */
  private static final double ADDRESS_SIMILARITY_MIN = 0.4;

  private final StoreRepository storeRepository;
  private final ProductCodeRepository productCodeRepository;
  private final ProductStoreLabelRepository labelRepository;
  private final ProductRepository productRepository;
  private final ReceiptProperties receiptProperties;
  private final CatalogProperties catalogProperties;

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
   * Kroky 1–2 kaskády pro řádky, které ještě nikdo ručně nepotvrdil. Řádky spárované
   * uživatelem ({@code USER}/{@code NEW_PRODUCT}), ignorované nebo s cenou se nemění — po
   * změně obchodu se tak přepočítají jen strojové návrhy.
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
      Optional<Long> productId = Optional.empty();
      ReceiptMatchSource source = null;
      if (store != null) {
        productId = byCode(line.getInternalCode(), chainId);
        source = ReceiptMatchSource.CODE;
        if (productId.isEmpty()) {
          productId = labelRepository.findExact(line.getLabelRaw(), store.getId(), chainId, viewerId).stream()
              .findFirst().map(ProductStoreLabel::getProductId);
          source = ReceiptMatchSource.LABEL;
        }
      }
      if (productId.isPresent()) {
        line.setProductId(productId.get());
        line.setMatchSource(source);
        line.setStatus(ReceiptLineStatus.SUGGESTED);
      } else {
        line.setProductId(null);
        line.setMatchSource(null);
        line.setStatus(ReceiptLineStatus.UNMATCHED);
      }
    }
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
   * trigramů.
   */
  @Transactional(readOnly = true)
  public List<Product> suggestions(ReceiptLine line, Store store, String query, Long viewerId, int limit) {
    Map<Long, Product> result = new LinkedHashMap<>();
    Long storeId = store != null ? store.getId() : null;
    Long chainId = store != null && store.getChain() != null ? store.getChain().getId() : null;
    if (store != null) {
      List<Long> labelProductIds = labelRepository.findSimilar(line.getLabelRaw(), storeId, chainId,
              receiptProperties.getLabelSimilarity(), limit).stream()
          .map(ProductStoreLabel::getProductId).toList();
      productRepository.findAllById(labelProductIds).forEach(p -> result.putIfAbsent(p.getId(), p));
    }
    String name = query != null && !query.isBlank() ? query.trim() : searchableLabel(line.getLabelRaw());
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
