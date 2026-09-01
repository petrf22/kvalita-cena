package cz.kvalitacena.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.config.MediaProperties;
import cz.kvalitacena.config.ReviewProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Denní strop na zakládání obchodů/zboží/fotek/textů recenzí na uživatele — stejný vzor jako
 * {@link OtpRateLimiter} (in-memory Caffeine, přežije jen do restartu, pro MVP stačí). Na
 * rozdíl od zápisu ceny (submitObservation, funguje i anonymně) je založení katalogu, upload
 * fotky i text recenze vždy vázané na přihlášeného uživatele, takže klíčem je rovnou
 * public_uid, ne hash e-mailu/IP.
 */
@Component
@RequiredArgsConstructor
public class CatalogRateLimiter {

  private final CatalogProperties catalogProperties;
  private final MediaProperties mediaProperties;
  private final ReviewProperties reviewProperties;

  private final Cache<UUID, AtomicInteger> storesPerDay = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofDays(1))
      .build();

  private final Cache<UUID, AtomicInteger> productsPerDay = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofDays(1))
      .build();

  private final Cache<UUID, AtomicInteger> mediaUploadsPerDay = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofDays(1))
      .build();

  private final Cache<UUID, AtomicInteger> reviewTextsPerDay = Caffeine.newBuilder()
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

  /** Jen zápis/úprava TEXTU (rateProduct bez textu limitem neprochází — je to jen číslo). */
  public boolean tryAcquireReviewText(UUID viewerPublicUid) {
    return tryIncrement(reviewTextsPerDay, viewerPublicUid, reviewProperties.getMaxPerDay());
  }

  private boolean tryIncrement(Cache<UUID, AtomicInteger> cache, UUID key, int max) {
    AtomicInteger counter = cache.get(key, k -> new AtomicInteger(0));
    return counter.incrementAndGet() <= max;
  }
}
