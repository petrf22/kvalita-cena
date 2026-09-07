package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.db.repo.ProductCodeRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductStoreLabelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Kaskáda párování řádku účtenky na katalogovou položku (docs/rozvoj.md, „Mapování obchodního
 * označení zboží na katalogovou položku"). První, co sedne, vyhrává:
 *
 * <ol>
 *   <li>vnitroobchodní kód z řádku → {@code core.product_code} (STORE_INTERNAL + chain_id),</li>
 *   <li>přesná shoda označení v rozsahu → {@code core.product_store_label},</li>
 *   <li>podobnost přes pg_trgm proti označením, pak proti aliasům a názvům zboží,</li>
 *   <li>nic.</li>
 * </ol>
 *
 * <p>Kroky 1–2 jsou strojově jisté ({@code MATCHED}), 3 je jen NÁVRH k potvrzení
 * ({@code SUGGESTED}) — stroj nikdy nepotvrzuje sám (docs/ai.md, „AI nikdy nerozhoduje").
 * Krok 4 nechává řádek {@code UNMATCHED}; nová katalogová položka se z účtenky nezakládá
 * automaticky, protože zakládání má vlastní limity a práh potvrzení (docs/reputace.md).
 */
@Service
@RequiredArgsConstructor
public class ReceiptLineMatchingService {

  private final ProductCodeRepository productCodeRepository;
  private final ProductStoreLabelRepository labelRepository;
  private final ProductRepository productRepository;
  private final CatalogProperties catalogProperties;

  public void matchAll(Receipt receipt, List<ReceiptLine> lines) {
    for (ReceiptLine line : lines) {
      if (line.getKind() == ReceiptLineKind.ITEM) {
        match(receipt, line);
      }
    }
  }

  public void match(Receipt receipt, ReceiptLine line) {
    if (byInternalCode(receipt, line) || byExactLabel(receipt, line) || bySimilarity(receipt, line)) {
      return;
    }
    line.setMatchStatus(ReceiptLineMatchStatus.UNMATCHED);
    line.setMatchedProductId(null);
    line.setMatchSource(null);
    line.setMatchScore(null);
  }

  /** Krok 1 — jistota, ale funguje jen když účtenka kód vytiskne (Albert ne). */
  private boolean byInternalCode(Receipt receipt, ReceiptLine line) {
    if (line.getRawCode() == null || receipt.getChainId() == null) {
      return false;
    }
    return productCodeRepository
        .findFirstByCodeAndCodeTypeAndChainId(line.getRawCode(), CodeType.STORE_INTERNAL,
            receipt.getChainId())
        .map(code -> {
          accept(line, code.getProduct().getId(), ReceiptMatchSource.CODE, null,
              ReceiptLineMatchStatus.MATCHED);
          return true;
        })
        .orElse(false);
  }

  /** Krok 2 — nejdřív provozovna, pak řetězec; pořadí je součást kaskády, ne detail SQL. */
  private boolean byExactLabel(Receipt receipt, ReceiptLine line) {
    if (receipt.getStoreId() == null && receipt.getChainId() == null) {
      return false;
    }
    return labelRepository
        .findActiveByLabel(line.getRawLabel(), receipt.getStoreId(), receipt.getChainId())
        .map(label -> {
          accept(line, label.getProductId(), ReceiptMatchSource.LABEL, null,
              ReceiptLineMatchStatus.MATCHED);
          return true;
        })
        .orElse(false);
  }

  /**
   * Krok 3 — jen návrh. Nejdřív mapovaná označení téhož rozsahu (nejbližší slovník), teprve
   * pak obecné názvy a aliasy zboží přes {@link ProductRepository#findSimilarByName}. Skóre
   * je maximum ze {@code similarity} a {@code word_similarity}, stejně jako u našeptávače.
   */
  private boolean bySimilarity(Receipt receipt, ReceiptLine line) {
    double threshold = catalogProperties.getSuggestionSimilarity();
    if (receipt.getStoreId() != null || receipt.getChainId() != null) {
      List<Object[]> similar = labelRepository.findSimilar(line.getRawLabel(),
          receipt.getStoreId(), receipt.getChainId(), threshold);
      if (!similar.isEmpty()) {
        Object[] row = similar.getFirst();
        accept(line, ((Number) row[0]).longValue(), ReceiptMatchSource.SIMILARITY,
            (BigDecimal) row[1], ReceiptLineMatchStatus.SUGGESTED);
        return true;
      }
    }
    List<Product> byName = productRepository.findSimilarByName(line.getRawLabel(),
        receipt.getStoreId(), null, threshold, 1);
    if (byName.isEmpty()) {
      return false;
    }
    accept(line, byName.getFirst().getId(), ReceiptMatchSource.SIMILARITY, null,
        ReceiptLineMatchStatus.SUGGESTED);
    return true;
  }

  private static void accept(ReceiptLine line, Long productId, ReceiptMatchSource source,
      BigDecimal score, ReceiptLineMatchStatus status) {
    line.setMatchedProductId(productId);
    line.setMatchSource(source);
    line.setMatchScore(score);
    line.setMatchStatus(status);
  }
}
