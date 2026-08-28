package cz.kvalitacena.service;

import cz.kvalitacena.config.OpenFoodFactsProperties;
import cz.kvalitacena.db.entity.OffFetchStatus;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.repo.OffProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Perzistentní OFF cache se soft-fail chováním; výpadek nikdy neblokuje ruční založení. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenFoodFactsService {

  private final OffProductRepository repository;
  private final OpenFoodFactsApiClient apiClient;
  private final OpenFoodFactsProperties properties;
  private final OffCategoryMapper categoryMapper;
  private final Clock clock = Clock.systemUTC();

  private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
  private final ArrayDeque<Long> requestTimes = new ArrayDeque<>();

  public OffLookupResult lookup(String rawCode) {
    String gtin = normalize(rawCode);
    Optional<OffProduct> cached = repository.findById(gtin);
    if (cached.filter(this::isFresh).isPresent()) return fromCache(cached.orElseThrow());
    if (!properties.isEnabled()) return staleOrUnavailable(cached);

    Object lock = locks.computeIfAbsent(gtin, ignored -> new Object());
    try {
      synchronized (lock) {
        cached = repository.findById(gtin);
        if (cached.filter(this::isFresh).isPresent()) return fromCache(cached.orElseThrow());
        if (!tryAcquire()) return staleOrUnavailable(cached);
        return fetchAndStore(gtin, cached);
      }
    } finally {
      locks.remove(gtin, lock);
    }
  }

  private OffLookupResult fetchAndStore(String gtin, Optional<OffProduct> stale) {
    String ean = gtin.replaceFirst("^0+(?!$)", "");
    try {
      Optional<OffRemoteProduct> remote = apiClient.fetch(ean);
      OffProduct snapshot = remote.map(p -> found(gtin, p)).orElseGet(() -> notFound(gtin));
      snapshot = repository.save(snapshot);
      return fromCache(snapshot);
    } catch (RestClientException e) {
      log.warn("Dotaz do Open Food Facts pro GTIN {} selhal: {}", gtin, e.getMessage());
      return staleOrUnavailable(stale);
    }
  }

  private OffProduct found(String gtin, OffRemoteProduct p) {
    return OffProduct.builder()
        .gtin(gtin).fetchStatus(OffFetchStatus.FOUND).productName(p.productName())
        .brandName(p.brandName()).productQuantity(p.productQuantity())
        .productQuantityUnit(p.productQuantityUnit()).categoryTags(p.categoryTags())
        .mappedCategorySlug(categoryMapper.categorySlugFor(p.categoryTags()))
        .imageFrontUrl(p.imageFrontUrl()).imageFrontSmallUrl(p.imageFrontSmallUrl())
        .sourceRevision(p.revision()).sourceUpdatedAt(p.updatedAt()).fetchedAt(now()).build();
  }

  private OffProduct notFound(String gtin) {
    return OffProduct.builder().gtin(gtin).fetchStatus(OffFetchStatus.NOT_FOUND)
        .fetchedAt(now()).build();
  }

  private boolean isFresh(OffProduct product) {
    var ttl = product.getFetchStatus() == OffFetchStatus.FOUND
        ? properties.getPositiveCacheTtl() : properties.getNegativeCacheTtl();
    return !product.getFetchedAt().plus(ttl).isBefore(now());
  }

  private OffLookupResult fromCache(OffProduct product) {
    return product.getFetchStatus() == OffFetchStatus.FOUND
        ? OffLookupResult.found(product) : OffLookupResult.notFound(product);
  }

  private OffLookupResult staleOrUnavailable(Optional<OffProduct> stale) {
    return stale.filter(p -> p.getFetchStatus() == OffFetchStatus.FOUND)
        .map(OffLookupResult::found).orElseGet(OffLookupResult::unavailable);
  }

  private synchronized boolean tryAcquire() {
    long now = clock.millis();
    long windowStart = now - 60_000;
    while (!requestTimes.isEmpty() && requestTimes.peekFirst() <= windowStart) requestTimes.removeFirst();
    if (requestTimes.size() >= properties.getMaxRequestsPerMinute()) return false;
    requestTimes.addLast(now);
    return true;
  }

  private String normalize(String rawCode) {
    if (!GtinNormalization.isValidCode(rawCode)) {
      throw new IllegalArgumentException("Barcode must contain 8 to 14 digits");
    }
    return GtinNormalization.toGtin14(rawCode);
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
  }
}
