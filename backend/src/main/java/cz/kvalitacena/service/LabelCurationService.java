package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.config.ReceiptProperties;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ReceiptLineRepository;
import cz.kvalitacena.db.repo.RetailChainRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Kurátorství označení z účtenek provozovatelem (tools/uctenky/curate.py, docs/rozvoj.md,
 * „Mapování obchodního označení zboží na katalogovou položku").
 *
 * <p>Provozovatel vidí nespárovaná označení <b>seskupená</b> podle označení a rozsahu
 * (řetězec, u samostatné prodejny prodejna) — bez účtenky, bez uživatele a s datem jen na den
 * (docs/soukromi.md, „Účtenky"). Do katalogu jde jen samotné označení, stejně jako při
 * pozdějším párování.
 *
 * <p>Mapování od provozovatele je vědomá výjimka z „označení se učí jen z potvrzeného řádku,
 * který vedl k zápisu ceny": platí hned ({@code ACTIVE}, {@code source = OPERATOR}), protože
 * ho dělá člověk, který katalog spravuje, a nespárované řádky ostatních tím jen dostanou
 * návrh — cenu dál zapisuje výhradně potvrzení vlastníka účtenky.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabelCurationService {

  private static final int MAX_LABELS = 500;
  private static final int MAX_PRODUCTS = 20;

  private final NamedParameterJdbcTemplate jdbc;
  private final ProductRepository productRepository;
  private final ProductOverlayService productOverlayService;
  private final RetailChainRepository chainRepository;
  private final StoreRepository storeRepository;
  private final ReceiptLineRepository receiptLineRepository;
  private final ReceiptProperties receiptProperties;
  private final CatalogProperties catalogProperties;

  /** Jedno nespárované označení v jednom rozsahu. {@code sampleStoreId} slouží jen k hledání zboží v rozsahu. */
  public record UnmatchedLabel(String label, String labelNorm, String labelDisplay, Long chainId, String chainName,
      Long storeId, String storeName, Long sampleStoreId, BigDecimal netContentValue, String netContentUom,
      int lineCount, LocalDate lastSeenDay) {
  }

  public record ProductCandidate(Long id, String name, String brand, BigDecimal netContentValue,
      String netContentUom, boolean variableWeight, String catalogScope, String status, double score) {
  }

  public record LabelMappingRequest(String label, Long chainId, Long storeId, Long productId, boolean replace) {
  }

  public enum Outcome { CREATED, UPDATED, UNCHANGED, CONFLICT, INVALID }

  public record LabelMappingResult(Outcome outcome, Long productId, Long existingProductId, int suggestedLines,
      String message) {
  }

  @Transactional(readOnly = true)
  public List<UnmatchedLabel> unmatched(Long chainId, Integer limit) {
    int size = Math.clamp(limit == null ? 100 : limit, 1, MAX_LABELS);
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("chainId", chainId)
        .addValue("notBefore", OffsetDateTime.now().minusDays(receiptProperties.getRematchMaxAgeDays()))
        .addValue("limit", size);
    // Rozsah stejně jako ProductStoreLabelService.learnFromLine: řetězec, jinak prodejna.
    return jdbc.query("""
        SELECT (array_agg(l.label_raw ORDER BY l.updated_at DESC))[1] AS label,
               l.label_norm,
               (array_agg(l.label_display ORDER BY l.updated_at DESC)
                 FILTER (WHERE l.label_display IS NOT NULL))[1] AS label_display,
               s.chain_id, c.name AS chain_name,
               CASE WHEN s.chain_id IS NULL THEN s.id END AS store_id,
               CASE WHEN s.chain_id IS NULL THEN s.name END AS store_name,
               max(s.id) AS sample_store_id,
               (array_agg(l.net_content_hint_value ORDER BY l.updated_at DESC)
                 FILTER (WHERE l.net_content_hint_uom IS NOT NULL))[1] AS hint_value,
               (array_agg(l.net_content_hint_uom ORDER BY l.updated_at DESC)
                 FILTER (WHERE l.net_content_hint_uom IS NOT NULL))[1] AS hint_uom,
               count(*) AS line_count,
               max(COALESCE(r.purchased_at, r.created_at))::date AS last_seen_day
        FROM core.receipt_line l
        JOIN core.receipt r ON r.id = l.receipt_id
        JOIN core.store s ON s.id = COALESCE(r.store_id, r.suggested_store_id)
        LEFT JOIN core.retail_chain c ON c.id = s.chain_id
        WHERE l.observation_id IS NULL
          AND (l.status = 'UNMATCHED' OR (l.status = 'SUGGESTED' AND l.match_source = 'FUZZY'))
          AND COALESCE(r.purchased_at, r.created_at) >= :notBefore
          AND (CAST(:chainId AS BIGINT) IS NULL OR s.chain_id = CAST(:chainId AS BIGINT))
        GROUP BY l.label_norm, s.chain_id, c.name,
                 CASE WHEN s.chain_id IS NULL THEN s.id END, CASE WHEN s.chain_id IS NULL THEN s.name END
        ORDER BY count(*) DESC, max(COALESCE(r.purchased_at, r.created_at)) DESC
        LIMIT :limit
        """, params, (rs, row) -> new UnmatchedLabel(
        rs.getString("label"), rs.getString("label_norm"), rs.getString("label_display"),
        (Long) rs.getObject("chain_id", Long.class), rs.getString("chain_name"),
        (Long) rs.getObject("store_id", Long.class), rs.getString("store_name"),
        rs.getLong("sample_store_id"), rs.getBigDecimal("hint_value"), rs.getString("hint_uom"),
        rs.getInt("line_count"), toLocalDate(rs.getDate("last_seen_day"))));
  }

  /** Zboží v rozsahu prodejny (a jejího řetězce) podobné dotazu, se skóre — stejné měření jako odhad. */
  @Transactional(readOnly = true)
  public List<ProductCandidate> searchProducts(String query, Long storeId, Integer limit) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    int size = Math.clamp(limit == null ? 10 : limit, 1, MAX_PRODUCTS);
    List<ProductRepository.ScoredProduct> rows = productRepository.findSimilarByNameScored(query.trim(), storeId,
        null, catalogProperties.getSuggestionSimilarity(), size * 2);
    Map<Long, Double> scores = new LinkedHashMap<>();
    rows.forEach(row -> scores.putIfAbsent(row.getId(), row.getScore()));
    Map<Long, Product> products = productOverlayService.applyOverlay(
            productRepository.findWithBrandAndCategoryByIdIn(scores.keySet()), null).stream()
        .collect(Collectors.toMap(Product::getId, Function.identity()));
    return scores.entrySet().stream()
        .filter(entry -> products.containsKey(entry.getKey()))
        .limit(size)
        .map(entry -> toCandidate(products.get(entry.getKey()), entry.getValue()))
        .toList();
  }

  /**
   * Mapování označení → zboží od provozovatele. Když označení v rozsahu už míří na JINÉ zboží,
   * bez {@code replace} se nic nemění ({@link Outcome#CONFLICT}) — tím se zároveň dá opravit
   * špatně naučené označení (docs/rozvoj.md, otevřená otázka „kdo opravuje mapování").
   */
  @Transactional
  public LabelMappingResult map(LabelMappingRequest request) {
    String label = request.label() == null ? "" : request.label().trim().replaceAll("\\s+", " ");
    if (label.length() < 2 || label.length() > 200) {
      return invalid("označení musí mít 2–200 znaků");
    }
    if ((request.chainId() == null) == (request.storeId() == null)) {
      return invalid("zadej právě jeden rozsah: chainId, nebo storeId");
    }
    if (request.chainId() != null && !chainRepository.existsById(request.chainId())) {
      return invalid("řetězec " + request.chainId() + " neexistuje");
    }
    if (request.storeId() != null && !storeRepository.existsById(request.storeId())) {
      return invalid("prodejna " + request.storeId() + " neexistuje");
    }
    Product product = productRepository.findById(request.productId() == null ? -1 : request.productId()).orElse(null);
    if (product == null || (product.getStatus() != ProductStatus.ACTIVE && product.getStatus() != ProductStatus.DRAFT)) {
      return invalid("zboží " + request.productId() + " neexistuje nebo není aktivní");
    }

    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("label", label)
        .addValue("chainId", request.chainId())
        .addValue("storeId", request.storeId())
        .addValue("productId", product.getId());
    List<Map<String, Object>> existing = jdbc.queryForList("""
        SELECT id, product_id, label_norm FROM core.product_store_label
        WHERE label_norm = core.norm_label(:label)
          AND COALESCE(chain_id, 0) = COALESCE(CAST(:chainId AS BIGINT), 0)
          AND COALESCE(store_id, 0) = COALESCE(CAST(:storeId AS BIGINT), 0)
        FOR UPDATE
        """, params);

    Outcome outcome;
    String labelNorm;
    if (existing.isEmpty()) {
      labelNorm = jdbc.queryForObject("""
          INSERT INTO core.product_store_label(product_id, label, chain_id, store_id, status, source, activated_at)
          VALUES (:productId, :label, :chainId, :storeId, 'ACTIVE', 'OPERATOR', CURRENT_TIMESTAMP)
          RETURNING label_norm
          """, params, String.class);
      outcome = Outcome.CREATED;
    } else {
      Map<String, Object> row = existing.getFirst();
      long labelId = ((Number) row.get("id")).longValue();
      long previousProductId = ((Number) row.get("product_id")).longValue();
      labelNorm = (String) row.get("label_norm");
      params.addValue("labelId", labelId).addValue("previousProductId", previousProductId);
      if (previousProductId != product.getId()) {
        if (!request.replace()) {
          return new LabelMappingResult(Outcome.CONFLICT, product.getId(), previousProductId, 0,
              "označení už míří na zboží " + previousProductId + " — přepsat jen s replace=true");
        }
        // Hlasy patřily původnímu zboží, novému by jen nafukovaly důvěru.
        jdbc.update("DELETE FROM core.product_store_label_confirmation WHERE label_id = :labelId", params);
        // Strojové návrhy podle starého mapování (nepotvrzené řádky) se přesměrují.
        jdbc.update("""
            UPDATE core.receipt_line SET product_id = :productId, updated_at = CURRENT_TIMESTAMP
            WHERE label_norm = :labelNorm AND status = 'SUGGESTED' AND match_source = 'LABEL'
              AND observation_id IS NULL AND product_id = :previousProductId
            """, params.addValue("labelNorm", labelNorm));
        outcome = Outcome.UPDATED;
      } else {
        outcome = Outcome.UNCHANGED;
      }
      jdbc.update("""
          UPDATE core.product_store_label SET product_id = :productId, status = 'ACTIVE', source = 'OPERATOR',
            activated_at = COALESCE(activated_at, CURRENT_TIMESTAMP), last_seen_at = CURRENT_TIMESTAMP
          WHERE id = :labelId
          """, params);
    }

    int suggested = receiptLineRepository.suggestUnmatched(labelNorm, product.getId(), request.chainId(),
        request.storeId(), OffsetDateTime.now().minusDays(receiptProperties.getRematchMaxAgeDays()));
    log.info("Provozovatel namapoval označení „{}“ (chain={} store={}) na zboží {} — {}, {} řádků dostalo návrh",
        label, request.chainId(), request.storeId(), product.getId(), outcome, suggested);
    return new LabelMappingResult(outcome, product.getId(), null, suggested, null);
  }

  private static LabelMappingResult invalid(String message) {
    return new LabelMappingResult(Outcome.INVALID, null, null, 0, message);
  }

  private static ProductCandidate toCandidate(Product product, double score) {
    String brand = product.getBrand() != null ? product.getBrand().getName() : product.getExternalBrandName();
    return new ProductCandidate(product.getId(), product.getName(), brand, product.getNetContentValue(),
        product.getNetContentUom() == null ? null : product.getNetContentUom().name(), product.isVariableWeight(),
        product.getCatalogScope() == null ? null : product.getCatalogScope().name(), product.getStatus().name(), score);
  }

  private static LocalDate toLocalDate(Date date) {
    return date == null ? null : date.toLocalDate();
  }
}
