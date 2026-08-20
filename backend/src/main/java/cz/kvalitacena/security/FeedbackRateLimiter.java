package cz.kvalitacena.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cz.kvalitacena.config.FeedbackProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Denní strop na odeslání zpětné vazby (core.feedback) — stejný vzor jako
 * {@link OtpRateLimiter}/{@link CatalogRateLimiter} (in-memory Caffeine, přežije jen do
 * restartu, pro betu stačí). Na rozdíl od {@link CatalogRateLimiter} funguje i ANONYMNĚ
 * ({@code submitFeedback} nevyžaduje přihlášení), takže IP je tu jediná dostupná pojistka u
 * nepřihlášeného odesílatele — u přihlášeného se navíc počítá i podle {@code userId}, aby
 * NAT/sdílená IP (kancelář, mobilní operátor) nezablokovala všechny testery najednou.
 */
@Component
@RequiredArgsConstructor
public class FeedbackRateLimiter {

  private final FeedbackProperties feedbackProperties;

  private final Cache<String, AtomicInteger> perIpDaily = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofDays(1))
      .build();

  private final Cache<Long, AtomicInteger> perUserDaily = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofDays(1))
      .build();

  /** @return true, pokud limit dovoluje další odeslání (a zároveň ho započítá). */
  public boolean tryAcquire(String ipAddress, Long userId) {
    if (ipAddress != null && !tryIncrement(perIpDaily, ipAddress, feedbackProperties.getMaxPerDayPerIp())) {
      return false;
    }
    if (userId != null && !tryIncrement(perUserDaily, userId, feedbackProperties.getMaxPerDayPerUser())) {
      return false;
    }
    return true;
  }

  private <K> boolean tryIncrement(Cache<K, AtomicInteger> cache, K key, int max) {
    AtomicInteger counter = cache.get(key, k -> new AtomicInteger(0));
    return counter.incrementAndGet() <= max;
  }
}
