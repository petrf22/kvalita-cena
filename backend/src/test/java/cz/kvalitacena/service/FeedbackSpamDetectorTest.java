package cz.kvalitacena.service;

import cz.kvalitacena.config.FeedbackProperties;
import cz.kvalitacena.db.repo.FeedbackRepository;
import cz.kvalitacena.security.FeedbackChallengeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link FeedbackSpamDetector} — každý signál se testuje izolovaně, ať je jasné, KTERÝ pravidlo
 * padlo, když se appce jednoho dne sejde spam s legitimní zprávou (docs/nasazeni.md, "obrana
 * proti spamu").
 */
@ExtendWith(MockitoExtension.class)
class FeedbackSpamDetectorTest {

  @Mock
  private FeedbackRepository feedbackRepository;

  private FeedbackProperties feedbackProperties;
  private FeedbackSpamDetector detector;

  @BeforeEach
  void setUp() {
    feedbackProperties = new FeedbackProperties();
    feedbackProperties.getSpam().setQuarantineThreshold(50);
    feedbackProperties.getSpam().setMaxLinks(2);
    detector = new FeedbackSpamDetector(feedbackProperties, feedbackRepository);
    lenient().when(feedbackRepository.existsByMessageHashAndCreatedAtAfter(any(), any())).thenReturn(false);
  }

  @Test
  void cleanMessageHasZeroScore() {
    var result = detector.evaluate("appka mi dnes spadla při skenování", false,
        FeedbackChallengeService.Outcome.VALID, true, hash("appka mi dnes spadla při skenování"), null);

    assertThat(result.score()).isZero();
    assertThat(result.reasons()).isEmpty();
    assertThat(result.quarantine()).isFalse();
  }

  @Test
  void honeypotFilledScoresHighEnoughToQuarantineAlone() {
    var result = detector.evaluate("test", true, FeedbackChallengeService.Outcome.VALID, true,
        hash("test"), null);

    assertThat(result.reasons()).contains("HONEYPOT");
    assertThat(result.quarantine()).isTrue();
  }

  @Test
  void missingChallengeAddsSignal() {
    var result = detector.evaluate("test", false, FeedbackChallengeService.Outcome.INVALID, false,
        hash("test"), null);

    assertThat(result.reasons()).containsExactly("CHALLENGE_MISSING");
  }

  @Test
  void invalidChallengeAddsSignal() {
    var result = detector.evaluate("test", false, FeedbackChallengeService.Outcome.INVALID, true,
        hash("test"), null);

    assertThat(result.reasons()).containsExactly("CHALLENGE_INVALID");
  }

  @Test
  void tooFastChallengeAddsSignal() {
    var result = detector.evaluate("test", false, FeedbackChallengeService.Outcome.TOO_FAST, true,
        hash("test"), null);

    assertThat(result.reasons()).containsExactly("CHALLENGE_TOO_FAST");
  }

  @Test
  void duplicateMessageWithinWindowAddsSignal() {
    when(feedbackRepository.existsByMessageHashAndCreatedAtAfter(any(), any())).thenReturn(true);

    var result = detector.evaluate("opakovaná zpráva", false, FeedbackChallengeService.Outcome.VALID, true,
        hash("opakovaná zpráva"), null);

    assertThat(result.reasons()).contains("DUPLICATE_MESSAGE");
  }

  @Test
  void tooManyLinksAddsSignal() {
    var result = detector.evaluate("koukni sem http://a.cz http://b.cz http://c.cz", false,
        FeedbackChallengeService.Outcome.VALID, true, hash("x"), null);

    assertThat(result.reasons()).contains("TOO_MANY_LINKS");
  }

  @Test
  void messageWithinLinkLimitDoesNotTrigger() {
    var result = detector.evaluate("koukni sem http://a.cz a http://b.cz díky", false,
        FeedbackChallengeService.Outcome.VALID, true, hash("x"), null);

    assertThat(result.reasons()).doesNotContain("TOO_MANY_LINKS");
  }

  @Test
  void linkOnlyMessageAddsSignal() {
    var result = detector.evaluate("http://spam.example/xyz", false, FeedbackChallengeService.Outcome.VALID,
        true, hash("x"), null);

    assertThat(result.reasons()).contains("LINK_ONLY");
  }

  @Test
  void linkWithSurroundingTextIsNotLinkOnly() {
    var result = detector.evaluate("odkaz na chybu je tady http://example.cz/bug děkuji", false,
        FeedbackChallengeService.Outcome.VALID, true, hash("x"), null);

    assertThat(result.reasons()).doesNotContain("LINK_ONLY");
  }

  @Test
  void scoreAtOrAboveThresholdQuarantines() {
    feedbackProperties.getSpam().setQuarantineThreshold(40);
    var result = detector.evaluate("test", false, FeedbackChallengeService.Outcome.TOO_FAST, true,
        hash("test"), null); // CHALLENGE_TOO_FAST = 40

    assertThat(result.score()).isEqualTo(40);
    assertThat(result.quarantine()).isTrue();
  }

  @Test
  void scoreBelowThresholdDoesNotQuarantine() {
    feedbackProperties.getSpam().setQuarantineThreshold(41);
    var result = detector.evaluate("test", false, FeedbackChallengeService.Outcome.TOO_FAST, true,
        hash("test"), null);

    assertThat(result.quarantine()).isFalse();
  }

  @Test
  void loggedInUserAlwaysScoresZeroEvenWithHoneypot() {
    var result = detector.evaluate("test", true, FeedbackChallengeService.Outcome.INVALID, false,
        hash("test"), 42L);

    assertThat(result.score()).isZero();
    assertThat(result.reasons()).isEmpty();
    assertThat(result.quarantine()).isFalse();
  }

  private byte[] hash(String value) {
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
}
