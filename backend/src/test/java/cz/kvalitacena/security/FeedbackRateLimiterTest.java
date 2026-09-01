package cz.kvalitacena.security;

import cz.kvalitacena.config.FeedbackProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FeedbackRateLimiter} — vrstvené limity (docs/nasazeni.md, "obrana proti spamu"): IP,
 * podsíť, globální anonymní strop a uživatelský strop se kontrolují nezávisle, teprve pak se
 * (společně) inkrementují.
 */
class FeedbackRateLimiterTest {

  private FeedbackProperties feedbackProperties;
  private FeedbackRateLimiter limiter;

  @BeforeEach
  void setUp() {
    feedbackProperties = new FeedbackProperties();
    feedbackProperties.setMaxPerDayPerIp(2);
    feedbackProperties.setMaxPerDayPerSubnet(3);
    feedbackProperties.setMaxPerDayPerUser(2);
    feedbackProperties.setMaxAnonymousPerHour(100);
    feedbackProperties.setMaxAnonymousPerDay(100);
    limiter = new FeedbackRateLimiter(feedbackProperties);
  }

  @Test
  void allowsUpToConfiguredLimitPerIp() {
    assertThat(limiter.tryAcquire("203.0.113.5", null)).isTrue();
    assertThat(limiter.tryAcquire("203.0.113.5", null)).isTrue();
    assertThat(limiter.tryAcquire("203.0.113.5", null)).isFalse(); // třetí přes limit 2/den
  }

  @Test
  void differentIpsInSameSubnetShareSubnetLimit() {
    feedbackProperties.setMaxPerDayPerIp(100); // ať test cílí jen na podsíť, ne na IP strop
    assertThat(limiter.tryAcquire("203.0.113.1", null)).isTrue();
    assertThat(limiter.tryAcquire("203.0.113.2", null)).isTrue();
    assertThat(limiter.tryAcquire("203.0.113.3", null)).isTrue();
    assertThat(limiter.tryAcquire("203.0.113.4", null)).isFalse(); // čtvrtá IP v /24 přes limit 3
  }

  @Test
  void differentSubnetsAreIndependent() {
    feedbackProperties.setMaxPerDayPerIp(100);
    for (int i = 0; i < 3; i++) {
      assertThat(limiter.tryAcquire("203.0.113." + i, null)).isTrue();
    }
    assertThat(limiter.tryAcquire("198.51.100.1", null)).isTrue(); // jiná /24, vlastní strop
  }

  @Test
  void loggedInUserHasOwnDailyLimitIndependentOfIp() {
    assertThat(limiter.tryAcquire("203.0.113.5", 1L)).isTrue();
    assertThat(limiter.tryAcquire("203.0.113.6", 1L)).isTrue(); // jiná IP, stejný uživatel
    assertThat(limiter.tryAcquire("203.0.113.7", 1L)).isFalse(); // třetí přes uživatelský limit 2
  }

  @Test
  void rejectedAttemptDoesNotConsumeIpQuota() {
    feedbackProperties.setMaxPerDayPerIp(10); // test cílí na uživatelský limit, ne na IP strop
    feedbackProperties.setMaxPerDayPerUser(1);
    assertThat(limiter.tryAcquire("203.0.113.5", 1L)).isTrue();
    assertThat(limiter.tryAcquire("203.0.113.5", 1L)).isFalse(); // spadne na uživatelském limitu

    // Odmítnutý druhý pokus nesmí ukrajovat z IP stropu — dřívější verze inkrementovala IP
    // čítač, i když se odeslání kvůli jinému limitu (tady uživatelskému) nakonec odmítlo.
    assertThat(limiter.tryAcquire("203.0.113.5", 2L)).isTrue();
    assertThat(limiter.tryAcquire("203.0.113.5", 3L)).isTrue();
  }

  @Test
  void globalAnonymousHourlyCapAppliesOnlyToAnonymousSubmissions() {
    feedbackProperties.setMaxAnonymousPerHour(2);
    feedbackProperties.setMaxPerDayPerIp(100);
    feedbackProperties.setMaxPerDayPerSubnet(100);

    assertThat(limiter.tryAcquire("203.0.113.1", null)).isTrue();
    assertThat(limiter.tryAcquire("198.51.100.1", null)).isTrue();
    assertThat(limiter.tryAcquire("192.0.2.1", null)).isFalse(); // třetí anonymní přes globální strop

    // Přihlášený má vlastní limit, globální anonymní strop se ho netýká.
    assertThat(limiter.tryAcquire("192.0.2.1", 1L)).isTrue();
  }

  @Test
  void globalAnonymousDailyCapAppliesOnlyToAnonymousSubmissions() {
    feedbackProperties.setMaxAnonymousPerDay(1);
    feedbackProperties.setMaxAnonymousPerHour(100);
    feedbackProperties.setMaxPerDayPerIp(100);
    feedbackProperties.setMaxPerDayPerSubnet(100);

    assertThat(limiter.tryAcquire("203.0.113.1", null)).isTrue();
    assertThat(limiter.tryAcquire("198.51.100.1", null)).isFalse();
  }

  @Test
  void ipv6AddressesAreGroupedBySlash48() {
    feedbackProperties.setMaxPerDayPerIp(100);
    assertThat(limiter.tryAcquire("2001:db8:1::1", null)).isTrue();
    assertThat(limiter.tryAcquire("2001:db8:1::2", null)).isTrue();
    assertThat(limiter.tryAcquire("2001:db8:1::3", null)).isTrue();
    assertThat(limiter.tryAcquire("2001:db8:1::4", null)).isFalse(); // stejný /48 prefix, limit 3
  }
}
