package cz.kvalitacena.service;

import cz.kvalitacena.config.FeedbackProperties;
import cz.kvalitacena.db.repo.FeedbackRepository;
import cz.kvalitacena.security.FeedbackChallengeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skóruje jedno odeslání {@code core.feedback} podle několika nezávislých signálů, součet
 * rozhoduje o karanténě (docs/nasazeni.md, "obrana proti spamu"). Karanténa, ne tiché zahození
 * — falešný poplach by jinak znamenal ztracené hlášení testera, o kterém se nikdo nikdy
 * nedozví; {@code setFeedbackQuarantined} je cesta zpět, stejně jako u {@code record_flag}
 * {@code resolveFlags DISMISSED}.
 *
 * <p><b>Přihlášený odesílatel dostává vždy skóre 0</b> — prošel OTP, má vlastní denní strop
 * v {@link cz.kvalitacena.security.FeedbackRateLimiter} a je to zrovna ten kanál, kterým může
 * nahlásit, že mu appka nefunguje (proof-of-work na starším klientovi, rozbitý honeypot apod.).
 */
@Service
@RequiredArgsConstructor
public class FeedbackSpamDetector {

  private static final Pattern LINK = Pattern.compile("https?://|www\\.", Pattern.CASE_INSENSITIVE);
  private static final Pattern LETTER = Pattern.compile("\\p{L}");

  private final FeedbackProperties feedbackProperties;
  private final FeedbackRepository feedbackRepository;

  public record Result(int score, List<String> reasons, boolean quarantine) {}

  private record Signal(String reason, int score) {}

  public Result evaluate(String message, boolean honeypotFilled, FeedbackChallengeService.Outcome challengeOutcome,
      boolean challengeProvided, byte[] messageHash, Long viewerUserId) {
    if (viewerUserId != null) {
      return new Result(0, List.of(), false);
    }

    List<Signal> signals = new ArrayList<>();

    if (honeypotFilled) {
      signals.add(new Signal("HONEYPOT", 100));
    }

    if (!challengeProvided) {
      signals.add(new Signal("CHALLENGE_MISSING", 50));
    } else if (challengeOutcome == FeedbackChallengeService.Outcome.INVALID) {
      signals.add(new Signal("CHALLENGE_INVALID", 50));
    } else if (challengeOutcome == FeedbackChallengeService.Outcome.TOO_FAST) {
      signals.add(new Signal("CHALLENGE_TOO_FAST", 40));
    }

    if (messageHash != null
        && feedbackRepository.existsByMessageHashAndCreatedAtAfter(messageHash, OffsetDateTime.now().minusHours(24))) {
      signals.add(new Signal("DUPLICATE_MESSAGE", 60));
    }

    long linkCount = countLinks(message);
    if (linkCount > feedbackProperties.getSpam().getMaxLinks()) {
      signals.add(new Signal("TOO_MANY_LINKS", 30));
    }
    if (linkCount > 0 && !LETTER.matcher(stripLinks(message)).find()) {
      signals.add(new Signal("LINK_ONLY", 20));
    }

    int score = signals.stream().mapToInt(Signal::score).sum();
    List<String> reasons = signals.stream().map(Signal::reason).toList();
    boolean quarantine = score >= feedbackProperties.getSpam().getQuarantineThreshold();
    return new Result(score, reasons, quarantine);
  }

  private long countLinks(String message) {
    Matcher matcher = LINK.matcher(message);
    long count = 0;
    while (matcher.find()) count++;
    return count;
  }

  /** Odstraní celé tokeny obsahující odkaz (ne jen prefix "https://"), ať zbyde jen skutečný text. */
  private String stripLinks(String message) {
    StringBuilder remaining = new StringBuilder();
    for (String token : message.split("\\s+")) {
      if (!LINK.matcher(token).find()) {
        remaining.append(token).append(' ');
      }
    }
    return remaining.toString();
  }
}
