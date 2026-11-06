package cz.kvalitacena.service;

import cz.kvalitacena.config.ReceiptProperties;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductCreatedVia;
import cz.kvalitacena.db.entity.ProductScope;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.entity.RecomputeReason;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.ProductCodeRepository;
import cz.kvalitacena.db.repo.ProductMergeVoteRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Dopárování „zboží z účtenky" na skutečné zboží (docs/reputace.md, „Zboží z účtenky").
 *
 * <ul>
 *   <li>Vlastní data uživatele (jeho ceny a jeho řádky účtenek) se přesunou hned — nikomu
 *       jinému tím nic nemění.</li>
 *   <li>Když na zboží z účtenky nejsou data nikoho jiného, sloučí se celé hned
 *       ({@link ProductMergeService#mergeReceiptItem}).</li>
 *   <li>Jinak vznikne hlas {@code zdroj → cíl} ({@code core.product_merge_vote}). Sloučení všech
 *       (cizí ceny, označení z účtenek, spárované řádky) proběhne, až pro STEJNÝ cíl hlasuje
 *       {@code app.receipt.item-merge-confirmations} různých lidí — jeden omyl ani jeden zlý
 *       úmysl cizí historii nepřesune. Neshoda hlasů nesloučí nic, čeká na moderátora
 *       ({@code mergeProducts}).</li>
 *   <li>Změna hlasu na jiný cíl vezme s sebou i data, která předchozí hlas už přesunul
 *       ({@code moved_observation_ids}/{@code moved_line_ids}) — jinak by zůstala u starého cíle.</li>
 *   <li>Ceny musí po přesunu znamenat totéž co ceny cíle — za kg, l, kus nebo balení
 *       ({@link ProductMergeUnits}); to se ověří dřív, než se cokoli přesune.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptItemRematchService {

  private final ProductRepository productRepository;
  private final ProductCodeRepository productCodeRepository;
  private final ProductMergeVoteRepository voteRepository;
  private final AppUserRepository appUserRepository;
  private final ProductMergeService mergeService;
  private final ProductCatalogService catalogService;
  private final PriceAggregationService priceAggregationService;
  private final ReceiptProperties receiptProperties;
  private final ProductMergeUnits productMergeUnits;
  private final JdbcTemplate jdbcTemplate;

  /**
   * @param product  cílové zboží (po sloučení), jinak to, na které se přesunula vlastní data
   * @param merged   zboží z účtenky se sloučilo celé
   * @param votes    hlasů pro tenhle cíl (po sloučení počet, který ho rozhodl)
   */
  public record Result(Long productId, boolean merged, int votes, int votesRequired) {
  }

  @Transactional
  public Result rematch(UUID userPublicUid, Long sourceId, Long targetId) {
    if (userPublicUid == null) {
      throw new UnauthorizedException(ErrorCode.RECEIPT_REQUIRES_LOGIN);
    }
    AppUser user = appUserRepository.findByPublicUid(userPublicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.ACCOUNT_GONE));
    if (Objects.equals(sourceId, targetId)) {
      throw new ValidationException(ErrorCode.RECEIPT_ITEM_REMATCH_INVALID);
    }
    // Zámek zdroje — dva souběžné hlasy nesmí oba projít pod prahem ani sloučit dvakrát.
    jdbcTemplate.queryForList("SELECT id FROM core.product WHERE id = ? FOR UPDATE", Long.class, sourceId);
    Product source = productRepository.findById(sourceId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND));
    Product target = productRepository.findById(targetId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND));
    if (!rematchable(source) || !usable(target) || !scopeFits(source, target)) {
      throw new ValidationException(ErrorCode.RECEIPT_ITEM_REMATCH_INVALID);
    }

    // Změna názoru: co předchozí hlas přesunul na jiný cíl, se nejdřív vrátí na zboží z účtenky
    // a dál se s tím zachází jako s ostatními vlastními daty.
    Optional<Long> previous = voteRepository.targetOf(sourceId, user.getId());
    if (previous.isPresent() && !previous.get().equals(targetId)) {
      returnVotedData(source, user.getId());
    }
    // Před oběma větvemi (a po vrácení, ať se posoudí i vrácené ceny) — sloučení i přesun
    // vlastních dat by jinak smíchaly nesrovnatelné ceny. Odmítnutí vrátí celou transakci.
    ProductMergeUnits.Compatibility units = productMergeUnits.requireCompatible(
        source, target, ErrorCode.RECEIPT_ITEM_REMATCH_UNIT_MISMATCH);

    int required = Math.max(1, receiptProperties.getItemMergeConfirmations());
    if (!hasOthersData(sourceId, user.getId())) {
      merge(source, target);
      return new Result(targetId, true, 1, required);
    }

    List<Long> adopted = productMergeUnits.adoptTargetNetContent(sourceId, units, List.of(user.getId()));
    Moved moved = moveOwnData(sourceId, targetId, user.getId());
    voteRepository.vote(sourceId, targetId, user.getId(), moved.observationIds(), moved.lineIds(), adopted);
    int count = voteRepository.countVoters(sourceId, targetId);
    if (count >= required) {
      merge(source, target);
      return new Result(targetId, true, count, required);
    }
    catalogService.promoteIfConfirmed(targetId);
    log.info("Zboží z účtenky {}: hlas pro {} ({} z {}), vlastní data přesunuta", sourceId, targetId, count, required);
    return new Result(targetId, false, count, required);
  }

  private void merge(Product source, Product target) {
    mergeService.mergeReceiptItem(source.getId(), target.getId());
    if (target.getCreatedVia() != ProductCreatedVia.RECEIPT) {
      // Řádky teď míří na skutečné zboží — už to není „zapsáno na zboží z účtenky".
      jdbcTemplate.update("UPDATE core.receipt_line SET match_source = 'LABEL' "
          + "WHERE product_id = ? AND match_source = 'RECEIPT_ITEM'", target.getId());
    }
    learnCodes(target);
    voteRepository.deleteForSource(source.getId());
    catalogService.promoteIfConfirmed(target.getId());
  }

  /**
   * EAN z řádků, které teď míří na zboží s EAN-ovou identitou a zatím bez kódu — stejné pravidlo
   * jako ReceiptReviewService.learnCode: jen globální GTIN, jen zboží, které žádný GTIN nemá.
   */
  private void learnCodes(Product target) {
    if (target.isGeneric()) {
      return;
    }
    List<String> codes = jdbcTemplate.queryForList(
        "SELECT DISTINCT internal_code FROM core.receipt_line WHERE product_id = ? AND internal_code IS NOT NULL",
        String.class, target.getId());
    for (String code : codes) {
      if (GtinNormalization.isGlobalGtin(code)
          && productCodeRepository.insertGtinIfProductHasNone(target.getId(), GtinNormalization.toGtin14(code.trim())) == 1) {
        return;
      }
    }
  }

  private static boolean rematchable(Product product) {
    return product.getCreatedVia() == ProductCreatedVia.RECEIPT && usable(product);
  }

  private static boolean usable(Product product) {
    return (product.getStatus() == ProductStatus.ACTIVE || product.getStatus() == ProductStatus.DRAFT)
        && product.getHiddenAt() == null;
  }

  /**
   * Cíl musí jít použít v obchodech, kde zboží z účtenky má ceny: globální zboží všude, lokální
   * jen ve stejném řetězci/provozovně (ProductScopeService). Bezkódové zboží z účtenky má rozsah
   * lokální, takže stačí porovnat rozsahy; zboží z účtenky s EAN je globální a pustí jen
   * globální cíl (ceny může mít z kteréhokoli řetězce).
   */
  private static boolean scopeFits(Product source, Product target) {
    return switch (target.getCatalogScope()) {
      case GLOBAL, LEGACY_GLOBAL -> true;
      case CHAIN -> source.getCatalogScope() == ProductScope.CHAIN && target.getScopeChain() != null
          && source.getScopeChain() != null && target.getScopeChain().getId().equals(source.getScopeChain().getId());
      case STORE -> source.getCatalogScope() == ProductScope.STORE && target.getScopeStore() != null
          && source.getScopeStore() != null && target.getScopeStore().getId().equals(source.getScopeStore().getId());
    };
  }

  /**
   * Ceny, potvrzené řádky účtenek nebo hlas někoho jiného. Cizí řádek, který je jen návrh
   * ({@code SUGGESTED} — naučené označení ho po zveřejnění přepnulo na tohle zboží), se
   * nepočítá: cena z něj nevznikla a se sloučením se jen přesune na cíl.
   * Cena bez autora (pseudonymizovaná po 180 dnech) je taky cizí — nikdo už nemůže doložit, že je jeho. Cizí hlas se počítá proto, že kdo
   * hlasoval, už svá data přesunul: bez něj by druhý hlas pro JINÝ cíl našel zboží „bez cizích
   * dat" a sloučil ho sám, i s označením z účtenek, o kterém se ti dva neshodli.
   */
  private boolean hasOthersData(Long sourceId, Long userId) {
    Boolean others = jdbcTemplate.queryForObject("""
        SELECT EXISTS (SELECT 1 FROM core.price_observation
                        WHERE product_id = ? AND submitter_id IS DISTINCT FROM ?)
            OR EXISTS (SELECT 1 FROM core.receipt_line l JOIN core.receipt r ON r.id = l.receipt_id
                        WHERE l.product_id = ? AND r.owner_user_id <> ? AND l.status = 'MATCHED')
            OR EXISTS (SELECT 1 FROM core.product_merge_vote
                        WHERE source_product_id = ? AND user_id IS DISTINCT FROM ?)
        """, Boolean.class, sourceId, userId, sourceId, userId, sourceId, userId);
    return Boolean.TRUE.equals(others);
  }

  /**
   * Vlastní ceny a řádky účtenek uživatele ze zboží z účtenky na cíl. Kolize s cenou, kterou
   * uživatel týž den k cíli už zapsal (uq_price_observation_submitter_kind_per_day), se vyřeší
   * ve prospěch cíle — stejně jako ProductMergeService.mergeObservations.
   */
  private record Moved(List<Long> observationIds, List<Long> lineIds) {
  }

  private Moved moveOwnData(Long sourceId, Long targetId, Long userId) {
    Set<Long> storeIds = new LinkedHashSet<>(jdbcTemplate.queryForList(
        "SELECT DISTINCT store_id FROM core.price_observation WHERE product_id = ? AND submitter_id = ?",
        Long.class, sourceId, userId));
    jdbcTemplate.update("""
        DELETE FROM core.price_observation source
        USING core.price_observation target
        WHERE source.product_id = ? AND target.product_id = ? AND source.submitter_id = ?
          AND target.submitter_id = source.submitter_id AND source.store_id = target.store_id
          AND source.price_kind = target.price_kind
          AND core.day_utc(source.observed_at) = core.day_utc(target.observed_at)
        """, sourceId, targetId, userId);
    List<Long> observationIds = jdbcTemplate.queryForList(
        "UPDATE core.price_observation SET product_id = ? WHERE product_id = ? AND submitter_id = ? RETURNING id",
        Long.class, targetId, sourceId, userId);
    List<Long> lineIds = jdbcTemplate.queryForList("""
        UPDATE core.receipt_line l SET product_id = ?, match_source = 'USER'
        FROM core.receipt r
        WHERE r.id = l.receipt_id AND l.product_id = ? AND r.owner_user_id = ?
        RETURNING l.id
        """, Long.class, targetId, sourceId, userId);
    for (Long storeId : storeIds) {
      priceAggregationService.enqueueRecompute(sourceId, storeId, RecomputeReason.MODERATION);
      priceAggregationService.enqueueRecompute(targetId, storeId, RecomputeReason.MODERATION);
    }
    return new Moved(observationIds, lineIds);
  }

  /**
   * Vrátí na zboží z účtenky vlastní data, která předchozí hlas přesunul jinam — ať už jsou
   * u původního cíle, nebo u zboží, do kterého se mezitím sloučil. Ceny za balení, které cíl
   * převedl na svou gramáž ({@link ProductMergeUnits#adoptTargetNetContent}, zapsané
   * v {@code adopted_observation_ids}), dostanou zpátky snapshot balení neznámé velikosti; jiné
   * ceny se nepřeváděly a jejich snapshot zůstane. Kolize s cenou, kterou uživatel
   * týž den zapsal přímo ke zboží z účtenky, se vyřeší ve prospěch té přímé.
   */
  private void returnVotedData(Product source, Long userId) {
    Long sourceId = source.getId();
    // CAST dělá z poddotazu skalární pole — samotné ANY(poddotaz) by porovnávalo id s celým polem.
    String movedObservations = "CAST((SELECT moved_observation_ids FROM core.product_merge_vote"
        + " WHERE source_product_id = ? AND user_id = ?) AS BIGINT[])";
    List<Map<String, Object>> cells = jdbcTemplate.queryForList(
        "SELECT DISTINCT product_id, store_id FROM core.price_observation WHERE id = ANY(" + movedObservations
            + ") AND submitter_id = ? AND product_id <> ?", sourceId, userId, userId, sourceId);
    jdbcTemplate.update("DELETE FROM core.price_observation moved USING core.price_observation kept"
        + " WHERE moved.id = ANY(" + movedObservations + ") AND moved.submitter_id = ? AND moved.product_id <> ?"
        + " AND kept.product_id = ? AND kept.submitter_id = moved.submitter_id AND kept.store_id = moved.store_id"
        + " AND kept.price_kind = moved.price_kind AND core.day_utc(kept.observed_at) = core.day_utc(moved.observed_at)",
        sourceId, userId, userId, sourceId, sourceId);
    String adoptedObservations = "CAST((SELECT adopted_observation_ids FROM core.product_merge_vote"
        + " WHERE source_product_id = ? AND user_id = ?) AS BIGINT[])";
    jdbcTemplate.update("UPDATE core.price_observation SET product_id = ?, net_content_base = CASE WHEN id = ANY("
        + adoptedObservations + ") THEN " + ProductMergeUnits.packageBaseSql()
        + " ELSE net_content_base END WHERE id = ANY(" + movedObservations + ") AND submitter_id = ? AND product_id <> ?",
        sourceId, sourceId, userId, sourceId, userId, userId, sourceId);
    jdbcTemplate.update("UPDATE core.receipt_line l SET product_id = ?, match_source = 'RECEIPT_ITEM'"
        + " FROM core.receipt r WHERE r.id = l.receipt_id AND l.id = ANY(CAST((SELECT moved_line_ids"
        + " FROM core.product_merge_vote WHERE source_product_id = ? AND user_id = ?) AS BIGINT[]))"
        + " AND r.owner_user_id = ? AND l.product_id IS DISTINCT FROM ?",
        sourceId, sourceId, userId, userId, sourceId);
    for (Map<String, Object> cell : cells) {
      Long productId = ((Number) cell.get("product_id")).longValue();
      Long storeId = ((Number) cell.get("store_id")).longValue();
      priceAggregationService.enqueueRecompute(productId, storeId, RecomputeReason.MODERATION);
      priceAggregationService.enqueueRecompute(sourceId, storeId, RecomputeReason.MODERATION);
    }
  }
}
