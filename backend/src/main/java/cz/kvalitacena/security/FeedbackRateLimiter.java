package cz.kvalitacena.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cz.kvalitacena.config.FeedbackProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Vrstvený denní/hodinový strop na odeslání zpětné vazby (core.feedback) — stejný vzor jako
 * {@link OtpRateLimiter}/{@link CatalogRateLimiter} (in-memory Caffeine, přežije jen do
 * restartu, pro betu stačí). Na rozdíl od {@link CatalogRateLimiter} funguje i ANONYMNĚ
 * ({@code submitFeedback} nevyžaduje přihlášení), takže IP a její podsíť jsou jediná dostupná
 * pojistka u nepřihlášeného odesílatele.
 *
 * <p>Vrstvy (docs/nasazeni.md, "obrana proti spamu"):
 * <ul>
 *   <li>IP/den — jeden útočník z jedné adresy</li>
 *   <li>podsíť/den (IPv4 /24, IPv6 /48) — rotace IP v jednom rozsahu</li>
 *   <li>globálně/hod a globálně/den — tvrdý strop proti náporu z mnoha různých IP, platí jen
 *       pro ANONYMNÍ odeslání (přihlášený má vlastní strop níž a prošel OTP)</li>
 *   <li>uživatel/den — přihlášený odesílatel</li>
 * </ul>
 *
 * <p>{@link #tryAcquire} nejdřív VŠECHNY vrstvy zkontroluje a teprve pak je inkrementuje — dřívější
 * verze inkrementovala IP čítač i tehdy, když pak spadla na uživatelském limitu, takže jedno
 * odmítnuté odeslání zbytečně ukrajovalo z IP stropu.
 */
@Component
@RequiredArgsConstructor
public class FeedbackRateLimiter {

  private final FeedbackProperties feedbackProperties;

  private final Cache<String, AtomicInteger> perIpDaily = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofDays(1))
      .build();

  private final Cache<String, AtomicInteger> perSubnetDaily = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofDays(1))
      .build();

  private final Cache<Long, AtomicInteger> perUserDaily = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofDays(1))
      .build();

  // Klíč je pořadové číslo hodiny/dne od epochy (hourBucket/dayBucket) — jeden čítač na okno,
  // Caffeine ho sám zahodí, jakmile okno vyprší. Jednodušší a bez race podmínky než ruční
  // "je čítač ještě platný, nebo ho vynulovat" nad jedním sdíleným AtomicInteger.
  private final Cache<Long, AtomicInteger> anonymousHourly = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofHours(1))
      .build();

  private final Cache<Long, AtomicInteger> anonymousDaily = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofDays(1))
      .build();

  /** @return true, pokud VŠECHNY vrstvy dovolují další odeslání (a zároveň ho započítají). */
  public boolean tryAcquire(String ipAddress, Long userId) {
    String subnet = ipAddress == null ? null : subnetOf(ipAddress);

    if (!checkOnly(ipAddress, subnet, userId)) return false;

    if (ipAddress != null) increment(perIpDaily, ipAddress);
    if (subnet != null) increment(perSubnetDaily, subnet);
    if (userId != null) {
      increment(perUserDaily, userId);
    } else {
      incrementGlobalAnonymousCounters();
    }
    return true;
  }

  private boolean checkOnly(String ipAddress, String subnet, Long userId) {
    if (ipAddress != null && peek(perIpDaily, ipAddress) >= feedbackProperties.getMaxPerDayPerIp()) {
      return false;
    }
    if (subnet != null && peek(perSubnetDaily, subnet) >= feedbackProperties.getMaxPerDayPerSubnet()) {
      return false;
    }
    if (userId != null) {
      return peek(perUserDaily, userId) < feedbackProperties.getMaxPerDayPerUser();
    }
    // Globální strop platí jen pro anonymní odeslání — přihlášený má svůj vlastní limit výš
    // a prošel OTP, není součástí "nápor z mnoha různých IP" scénáře.
    return peek(anonymousHourly, hourBucket()) < feedbackProperties.getMaxAnonymousPerHour()
        && peek(anonymousDaily, dayBucket()) < feedbackProperties.getMaxAnonymousPerDay();
  }

  private <K> int peek(Cache<K, AtomicInteger> cache, K key) {
    AtomicInteger counter = cache.getIfPresent(key);
    return counter == null ? 0 : counter.get();
  }

  private <K> void increment(Cache<K, AtomicInteger> cache, K key) {
    cache.get(key, k -> new AtomicInteger(0)).incrementAndGet();
  }

  private void incrementGlobalAnonymousCounters() {
    increment(anonymousHourly, hourBucket());
    increment(anonymousDaily, dayBucket());
  }

  private long hourBucket() {
    return System.currentTimeMillis() / Duration.ofHours(1).toMillis();
  }

  private long dayBucket() {
    return System.currentTimeMillis() / Duration.ofDays(1).toMillis();
  }

  /**
   * IPv4 → prvních 24 bitů (/24), IPv6 → prvních 48 bitů (/48) jako textový prefix. Appka tu jen
   * seskupuje pro rate limit, ne pro směrování — hrubá textová aproximace stačí, nemusí to být
   * skutečná síťová maska.
   */
  private String subnetOf(String ip) {
    if (ip.contains(":")) {
      String[] groups = ip.split(":");
      int take = Math.min(3, groups.length); // 3 × 16 bitů = 48 bitů
      return String.join(":", java.util.Arrays.copyOfRange(groups, 0, take));
    }
    String[] octets = ip.split("\\.");
    if (octets.length != 4) return ip; // nerozpoznaný formát — bez seskupení, jen ať appka nespadne
    return octets[0] + "." + octets[1] + "." + octets[2];
  }
}
