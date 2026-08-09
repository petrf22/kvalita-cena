package cz.kvalitacena.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.config.MediaProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Denní strop na zakládání obchodů/zboží/fotek na uživatele — stejný vzor jako
 * {@link OtpRateLimiter} (in-memory Caffeine, přežije jen do restartu, pro MVP stačí). Na
 * rozdíl od zápisu ceny (submitObservation, funguje i anonymně) je založení katalogu i upload
 * fotky vždy vázané na přihlášeného uživatele, takže klíčem je rovnou public_uid, ne hash
 * e-mailu/IP.
 */
@Component
@RequiredArgsConstructor
public class CatalogRateLimiter {

  private final CatalogProperties catalogProperties;
  private final MediaProperties mediaProperties;

  private final Cache<UUID, AtomicInteger> storesPerDay = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofDays(1))
      .build();

  private final Cache<UUID, AtomicInteger> productsPerDay = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofDays(1))
      .build();

  private final Cache<UUID, AtomicInteger> mediaUploadsPerDay = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofDays(1))
      .build();

  /** @return true, pokud limit dovoluje založení dalšího obchodu (a zároveň ho započítá). */
  public boolean tryAcquireStoreCreation(UUID viewerPublicUid) {
    return tryIncrement(storesPerDay, viewerPublicUid, catalogProperties.getMaxStoresPerDay());
  }

  public boolean tryAcquireProductCreation(UUID viewerPublicUid) {
    return tryIncrement(productsPerDay, viewerPublicUid, catalogProperties.getMaxProductsPerDay());
  }

  public boolean tryAcquireMediaUpload(UUID viewerPublicUid) {
    return tryIncrement(mediaUploadsPerDay, viewerPublicUid, mediaProperties.getMaxUploadsPerDay());
  }

  private boolean tryIncrement(Cache<UUID, AtomicInteger> cache, UUID key, int max) {
    AtomicInteger counter = cache.get(key, k -> new AtomicInteger(0));
    return counter.incrementAndGet() <= max;
  }
}
