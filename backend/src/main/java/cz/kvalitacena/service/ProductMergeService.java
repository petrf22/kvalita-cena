package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.db.entity.Media;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductScope;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.entity.RecordType;
import cz.kvalitacena.db.entity.RecomputeReason;
import cz.kvalitacena.db.repo.MediaRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.RecordFlagRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/** Moderátorské sloučení dvou duplicitních bezkódových produktů bez ztráty jejich vazeb. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductMergeService {

  private final ProductRepository productRepository;
  private final MediaRepository mediaRepository;
  private final MediaStorage mediaStorage;
  private final RecordFlagRepository recordFlagRepository;
  private final PriceAggregationService priceAggregationService;
  private final CatalogProperties catalogProperties;
  private final JdbcTemplate jdbcTemplate;

  @Transactional
  public Product merge(Long sourceId, Long targetId, Long moderatorUserId) {
    if (sourceId.equals(targetId)) {
      throw new ValidationException(ErrorCode.MODERATION_PRODUCT_MERGE_SAME);
    }
    Product source = productRepository.findById(sourceId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.MODERATION_RECORD_NOT_FOUND));
    Product target = productRepository.findById(targetId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.MODERATION_RECORD_NOT_FOUND));
    if (!source.isGeneric() || !target.isGeneric()
        || source.getStatus() == ProductStatus.MERGED || target.getStatus() == ProductStatus.MERGED
        || source.getStatus() == ProductStatus.REJECTED || target.getStatus() == ProductStatus.REJECTED) {
      throw new ValidationException(ErrorCode.MODERATION_PRODUCT_MERGE_INVALID);
    }
    if (!observationsFitTargetScope(sourceId, target)) {
      throw new ValidationException(ErrorCode.MODERATION_PRODUCT_MERGE_SCOPE_MISMATCH);
    }

    List<Long> storeIds = jdbcTemplate.queryForList(
        "SELECT DISTINCT store_id FROM core.price_observation WHERE product_id IN (?, ?)",
        Long.class, sourceId, targetId);

    removeDuplicateMedia(sourceId, targetId, moderatorUserId);
    mergeCodes(sourceId, targetId);
    mergeReviews(sourceId, targetId, moderatorUserId);
    mergeUserEdits(sourceId, targetId);
    mergeAliases(source, target);
    mergeObservations(sourceId, targetId);

    // Staré agregáty zdroje už nesmějí nikde přežít. Cíl se přepočítá z právě sjednocených
    // observací; frontu zdroje mažeme, jinak by plánovač znovu vytvořil osiřelé agregáty.
    jdbcTemplate.update("DELETE FROM agg.price_current WHERE product_id = ?", sourceId);
    jdbcTemplate.update("DELETE FROM agg.price_daily WHERE product_id = ?", sourceId);
    jdbcTemplate.update("DELETE FROM agg.recompute_queue WHERE product_id = ?", sourceId);

    jdbcTemplate.update("UPDATE core.media SET record_id = ? WHERE record_type = 'PRODUCT' AND record_id = ?",
        targetId, sourceId);
    recordFlagRepository.resolveAllPending(RecordType.PRODUCT.name(), sourceId, moderatorUserId, "UPHELD");
    jdbcTemplate.update("UPDATE core.product SET merged_into_id = ? WHERE merged_into_id = ?", targetId, sourceId);

    // hiddenAt se NEnuluje — flagy se uzavírají jako UPHELD (ModerationService.applyHiddenAt
    // pro UPHELD taky nikdy neodkrývá), zrušení moderátorského skrytí zdroje by tomu
    // odporovalo. Na viditelnost výsledku to nemá vliv: MERGED produkt se dál dohledá jen
    // přes mergedInto (ProductGraphQlController.product), findSimilarByName MERGED vynechává.
    source.setStatus(ProductStatus.MERGED);
    source.setMergedInto(target);
    productRepository.save(source);

    storeIds.forEach(storeId ->
        priceAggregationService.enqueueRecompute(targetId, storeId, RecomputeReason.MODERATION));
    log.info("Moderátor {} sloučil bezkódový produkt {} do {} ({} dotčených obchodů)",
        moderatorUserId, sourceId, targetId, storeIds.size());
    return target;
  }

  private boolean observationsFitTargetScope(Long sourceId, Product target) {
    if (target.getCatalogScope() == ProductScope.GLOBAL
        || target.getCatalogScope() == ProductScope.LEGACY_GLOBAL) {
      return true;
    }
    Long incompatible;
    if (target.getCatalogScope() == ProductScope.STORE) {
      incompatible = jdbcTemplate.queryForObject(
          "SELECT count(*) FROM core.price_observation WHERE product_id = ? AND store_id <> ?",
          Long.class, sourceId, target.getScopeStore().getId());
    } else {
      incompatible = jdbcTemplate.queryForObject("""
          SELECT count(*) FROM core.price_observation po
          JOIN core.store s ON s.id = po.store_id
          WHERE po.product_id = ? AND s.chain_id IS DISTINCT FROM ?
          """, Long.class, sourceId, target.getScopeChain().getId());
    }
    return incompatible != null && incompatible == 0;
  }

  private void removeDuplicateMedia(Long sourceId, Long targetId, Long moderatorUserId) {
    List<Media> targetMedia = mediaRepository
        .findByRecordTypeAndRecordIdOrderBySortOrderAscIdAsc(RecordType.PRODUCT, targetId);
    List<Media> duplicates = mediaRepository
        .findByRecordTypeAndRecordIdOrderBySortOrderAscIdAsc(RecordType.PRODUCT, sourceId).stream()
        .filter(source -> targetMedia.stream().anyMatch(target -> Arrays.equals(source.getSha256(), target.getSha256())))
        .toList();
    for (Media duplicate : duplicates) {
      jdbcTemplate.update("""
          UPDATE core.record_flag SET resolved_at = CURRENT_TIMESTAMP, resolved_by_user_id = ?, resolution = 'UPHELD'
          WHERE record_type = 'PHOTO' AND record_id = ? AND resolved_at IS NULL
          """, moderatorUserId, duplicate.getId());
      mediaStorage.delete(duplicate.getStorageKey());
      mediaRepository.delete(duplicate);
    }
    if (!duplicates.isEmpty()) mediaRepository.flush();
  }

  private void mergeCodes(Long sourceId, Long targetId) {
    jdbcTemplate.update("""
        DELETE FROM core.product_code source
        USING core.product_code target
        WHERE source.product_id = ? AND target.product_id = ?
          AND source.code = target.code AND source.code_type = target.code_type
          AND COALESCE(source.chain_id, 0) = COALESCE(target.chain_id, 0)
        """, sourceId, targetId);
    jdbcTemplate.update("UPDATE core.product_code SET product_id = ? WHERE product_id = ?", targetId, sourceId);
  }

  private void mergeReviews(Long sourceId, Long targetId, Long moderatorUserId) {
    jdbcTemplate.update("""
        UPDATE core.record_flag SET resolved_at = CURRENT_TIMESTAMP, resolved_by_user_id = ?, resolution = 'UPHELD'
        WHERE record_type = 'REVIEW' AND resolved_at IS NULL AND record_id IN (
          SELECT source.id FROM core.product_review source
          JOIN core.product_review target ON target.product_id = ? AND target.user_id = source.user_id
          WHERE source.product_id = ?)
        """, moderatorUserId, targetId, sourceId);
    // Při kolizi jednoho autora má přednost recenze už připojená k cíli; jinak by sloučení
    // tiše přepsalo text, který moderátor právě zvolil jako kanonický produkt.
    jdbcTemplate.update("""
        DELETE FROM core.product_review source
        USING core.product_review target
        WHERE source.product_id = ? AND target.product_id = ? AND source.user_id = target.user_id
        """, sourceId, targetId);
    jdbcTemplate.update("UPDATE core.product_review SET product_id = ? WHERE product_id = ?", targetId, sourceId);
  }

  private void mergeUserEdits(Long sourceId, Long targetId) {
    jdbcTemplate.update("""
        DELETE FROM core.product_user_edit source
        USING core.product_user_edit target
        WHERE source.product_id = ? AND target.product_id = ? AND source.user_id = target.user_id
        """, sourceId, targetId);
    jdbcTemplate.update("UPDATE core.product_user_edit SET product_id = ? WHERE product_id = ?", targetId, sourceId);
  }

  private void mergeAliases(Product source, Product target) {
    // Moderátorem potvrzený původní název zdroje je užitečný alias cíle i bez dalších hlasů.
    jdbcTemplate.update("""
        INSERT INTO core.product_alias(product_id, name, status, activated_at)
        SELECT ?, ?, 'ACTIVE', CURRENT_TIMESTAMP
        WHERE core.norm_text(?) <> core.norm_text(?)
        ON CONFLICT (product_id, core.norm_text(name))
        DO UPDATE SET status = 'ACTIVE', activated_at = COALESCE(core.product_alias.activated_at, CURRENT_TIMESTAMP)
        """, target.getId(), source.getName(), source.getName(), target.getName());

    jdbcTemplate.update("""
        UPDATE core.product_alias target SET
          status = CASE WHEN source.status = 'ACTIVE' THEN 'ACTIVE' ELSE target.status END,
          activated_at = CASE WHEN source.status = 'ACTIVE'
            THEN COALESCE(target.activated_at, source.activated_at, CURRENT_TIMESTAMP)
            ELSE target.activated_at END
        FROM core.product_alias source
        WHERE source.product_id = ? AND target.product_id = ?
          AND core.norm_text(source.name) = core.norm_text(target.name)
        """, source.getId(), target.getId());
    jdbcTemplate.update("""
        INSERT INTO core.product_alias_confirmation(alias_id, user_id, created_at)
        SELECT target.id, confirmation.user_id, confirmation.created_at
        FROM core.product_alias source
        JOIN core.product_alias target ON target.product_id = ?
          AND core.norm_text(target.name) = core.norm_text(source.name)
        JOIN core.product_alias_confirmation confirmation ON confirmation.alias_id = source.id
        WHERE source.product_id = ? AND confirmation.user_id IS NOT NULL
        ON CONFLICT (alias_id, user_id) WHERE user_id IS NOT NULL DO NOTHING
        """, target.getId(), source.getId());
    jdbcTemplate.update("""
        DELETE FROM core.product_alias source
        USING core.product_alias target
        WHERE source.product_id = ? AND target.product_id = ?
          AND core.norm_text(source.name) = core.norm_text(target.name)
        """, source.getId(), target.getId());
    jdbcTemplate.update("UPDATE core.product_alias SET product_id = ? WHERE product_id = ?",
        target.getId(), source.getId());
    jdbcTemplate.update("""
        UPDATE core.product_alias alias SET status = 'ACTIVE', activated_at = COALESCE(activated_at, CURRENT_TIMESTAMP)
        WHERE alias.product_id = ? AND alias.status = 'PENDING' AND (
          SELECT count(DISTINCT user_id) FROM core.product_alias_confirmation WHERE alias_id = alias.id
        ) >= ?
        """, target.getId(), catalogProperties.getAliasConfirmations());
  }

  private void mergeObservations(Long sourceId, Long targetId) {
    jdbcTemplate.update("""
        DELETE FROM core.price_observation source
        USING core.price_observation target
        WHERE source.product_id = ? AND target.product_id = ?
          AND source.store_id = target.store_id AND source.submitter_id = target.submitter_id
          AND source.submitter_id IS NOT NULL AND source.price_kind = target.price_kind
          AND core.day_utc(source.observed_at) = core.day_utc(target.observed_at)
        """, sourceId, targetId);
    jdbcTemplate.update("UPDATE core.price_observation SET product_id = ? WHERE product_id = ?", targetId, sourceId);
  }
}
