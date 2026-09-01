package cz.kvalitacena.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory limity na odesílání OTP kódů (Caffeine, ne Bucket4j — vědomá volba kvůli pravidlu
 * jen svobodných knihoven, CLAUDE.md, "Konvence"). Přežije jen do restartu aplikace, což pro
 * MVP stačí; perzistentní varianta (`auth.throttle_counter`) je budoucí rozvoj, zatím
 * nerozhodnuto kdy.
 */
@Component
public class OtpRateLimiter {

  // 1 požadavek / 60 s na e-mail (resend cooldown)
  private final Cache<String, AtomicInteger> emailCooldown = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofSeconds(60))
      .build();

  // 5 požadavků / hodinu na e-mail
  private final Cache<String, AtomicInteger> emailHourly = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofHours(1))
      .build();

  // 10 požadavků / den na e-mail
  private final Cache<String, AtomicInteger> emailDaily = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofDays(1))
      .build();

  // 20 požadavků / hodinu na IP
  private final Cache<String, AtomicInteger> ipHourly = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofHours(1))
      .build();

  /** @return true, pokud limit dovoluje další požadavek (a zároveň ho započítá). */
  public boolean tryAcquireForRequest(byte[] emailHash, String ipAddress) {
    String emailKey = key(emailHash);
    if (!tryIncrement(emailCooldown, emailKey, 1)) return false;
    if (!tryIncrement(emailHourly, emailKey, 5)) return false;
    if (!tryIncrement(emailDaily, emailKey, 10)) return false;
    if (ipAddress != null && !tryIncrement(ipHourly, ipAddress, 20)) return false;
    return true;
  }

  private boolean tryIncrement(Cache<String, AtomicInteger> cache, String key, int max) {
    AtomicInteger counter = cache.get(key, k -> new AtomicInteger(0));
    return counter.incrementAndGet() <= max;
  }

  private String key(byte[] emailHash) {
    return Base64.getEncoder().encodeToString(emailHash);
  }
}
