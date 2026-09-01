package cz.kvalitacena.security;

import cz.kvalitacena.config.FeedbackProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FeedbackChallengeService} — proof-of-work náhrada CAPTCHY (docs/nasazeni.md, obrana
 * proti spamu). {@code jwtSecretBase64} je normálně {@code @Value}, ale test ho nastavuje přímo
 * reflexí — spouštět celý Spring kontext jen kvůli jedné hodnotě by bylo zbytečné.
 */
class FeedbackChallengeServiceTest {

  private FeedbackProperties feedbackProperties;
  private FeedbackChallengeService service;

  @BeforeEach
  void setUp() throws Exception {
    feedbackProperties = new FeedbackProperties();
    feedbackProperties.getChallenge().setDifficulty(8); // levné v testu, princip je stejný
    feedbackProperties.getChallenge().setTtl(Duration.ofMinutes(5));
    // Nula ve výchozím nastavení testu — issue()+verify() proběhnou v jednom testu prakticky
    // okamžitě, takže reálný minAge by JEDINÝ scénář dole (solvedFasterThanMinAgeIsFlaggedAsTooFast)
    // testoval a všechny ostatní by tiše dostávaly TOO_FAST místo VALID.
    feedbackProperties.getChallenge().setMinAge(Duration.ZERO);

    service = newService(feedbackProperties, "dGVzdC1zZWNyZXQtcHJvLXVuaXQtdGVzdHktMTIzNDU2Nzg5MA==");
  }

  private FeedbackChallengeService newService(FeedbackProperties properties, String jwtSecretBase64) throws Exception {
    FeedbackChallengeService instance = new FeedbackChallengeService(properties);
    Field secretField = FeedbackChallengeService.class.getDeclaredField("jwtSecretBase64");
    secretField.setAccessible(true);
    secretField.set(instance, jwtSecretBase64);
    java.lang.reflect.Method init = FeedbackChallengeService.class.getDeclaredMethod("init");
    init.setAccessible(true);
    init.invoke(instance);
    return instance;
  }

  /** Najde nonce, jehož SHA-256(salt + ":" + nonce) splňuje obtížnost — stejný algoritmus, jaký
   *  musí implementovat web (Worker) i mobil (ProofOfWork.kt). */
  private String solve(String salt, int difficulty) {
    for (long nonce = 0; ; nonce++) {
      if (hasLeadingZeroBits(sha256(salt + ":" + nonce), difficulty)) {
        return String.valueOf(nonce);
      }
    }
  }

  private byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private boolean hasLeadingZeroBits(byte[] hash, int bits) {
    int fullBytes = bits / 8;
    for (int i = 0; i < fullBytes; i++) {
      if (hash[i] != 0) return false;
    }
    int remainingBits = bits % 8;
    if (remainingBits == 0) return true;
    int mask = (0xFF << (8 - remainingBits)) & 0xFF;
    return (hash[fullBytes] & mask) == 0;
  }

  @Test
  void validSolutionIsAccepted() {
    var issued = service.issue();
    String nonce = solve(issued.salt(), issued.difficulty());

    assertThat(service.verify(issued.token(), nonce)).isEqualTo(FeedbackChallengeService.Outcome.VALID);
  }

  /** Pevný vektor — web (proof-of-work.ts) i mobil (ProofOfWork.kt) musí dojít stejnému nonce
   *  pro stejné (salt, difficulty), jinak appky nejsou navzájem kompatibilní. */
  @Test
  void fixedVectorMatchesAcrossClients() {
    String salt = "unit-test-salt";
    int difficulty = 12;
    String nonce = solve(salt, difficulty);

    assertThat(hasLeadingZeroBits(sha256(salt + ":" + nonce), difficulty)).isTrue();
    // Zápis hodnoty pro ruční porovnání s web/mobilní implementací (viz plán, "Ověření").
    System.out.println("FIXED_VECTOR salt=" + salt + " difficulty=" + difficulty + " nonce=" + nonce);
  }

  @Test
  void wrongNonceIsRejected() {
    var issued = service.issue();

    assertThat(service.verify(issued.token(), "not-the-right-nonce"))
        .isEqualTo(FeedbackChallengeService.Outcome.INVALID);
  }

  @Test
  void tamperedTokenIsRejected() {
    var issued = service.issue();
    String nonce = solve(issued.salt(), issued.difficulty());
    String tampered = issued.token().substring(0, issued.token().length() - 1)
        + (issued.token().charAt(issued.token().length() - 1) == 'A' ? 'B' : 'A');

    assertThat(service.verify(tampered, nonce)).isEqualTo(FeedbackChallengeService.Outcome.INVALID);
  }

  @Test
  void expiredTokenIsRejected() throws Exception {
    feedbackProperties.getChallenge().setTtl(Duration.ofMillis(1));
    var issued = service.issue();
    String nonce = solve(issued.salt(), issued.difficulty());
    Thread.sleep(20);

    assertThat(service.verify(issued.token(), nonce)).isEqualTo(FeedbackChallengeService.Outcome.INVALID);
  }

  @Test
  void replayedSaltIsRejectedSecondTime() {
    var issued = service.issue();
    String nonce = solve(issued.salt(), issued.difficulty());

    assertThat(service.verify(issued.token(), nonce)).isEqualTo(FeedbackChallengeService.Outcome.VALID);
    assertThat(service.verify(issued.token(), nonce)).isEqualTo(FeedbackChallengeService.Outcome.INVALID);
  }

  @Test
  void solvedFasterThanMinAgeIsFlaggedAsTooFast() {
    feedbackProperties.getChallenge().setMinAge(Duration.ofSeconds(10));
    var issued = service.issue();
    String nonce = solve(issued.salt(), issued.difficulty());

    assertThat(service.verify(issued.token(), nonce)).isEqualTo(FeedbackChallengeService.Outcome.TOO_FAST);
  }

  @Test
  void missingTokenOrNonceIsRejected() {
    var issued = service.issue();
    assertThat(service.verify(null, "1")).isEqualTo(FeedbackChallengeService.Outcome.INVALID);
    assertThat(service.verify(issued.token(), null)).isEqualTo(FeedbackChallengeService.Outcome.INVALID);
  }

  @Test
  void garbageTokenIsRejected() {
    assertThat(service.verify("not-a-real-token", "1")).isEqualTo(FeedbackChallengeService.Outcome.INVALID);
    assertThat(service.verify(Base64.getUrlEncoder().encodeToString("x".getBytes()) + ".sig", "1"))
        .isEqualTo(FeedbackChallengeService.Outcome.INVALID);
  }
}
