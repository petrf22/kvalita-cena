package cz.kvalitacena.service;

import cz.kvalitacena.config.FeedbackProperties;
import cz.kvalitacena.controller.FeedbackInput;
import cz.kvalitacena.controller.FeedbackItem;
import cz.kvalitacena.controller.FeedbackItemResult;
import cz.kvalitacena.controller.FeedbackResult;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.ClientKind;
import cz.kvalitacena.db.entity.Feedback;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.FeedbackRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.TooManyRequestsException;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.EmailCipher;
import cz.kvalitacena.security.FeedbackRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Zpětná vazba od uživatele appky (core.feedback) — jediný first-party kanál pro uzavřenou
 * betu (docs/nasazeni.md, „Než pozvat první lidi"). Funguje i ANONYMNĚ, na rozdíl od
 * {@link RecordFlagService}: zrovna nepřihlášený tester narazí na nejcennější hlášení, třeba
 * že appka nedovolí přihlášení vůbec.
 *
 * <p>Vědomá odchylka od {@code record_flag} (docs/soukromi.md): autor SE vrací moderátorovi
 * (viz {@link #toItem}), protože bez něj není komu na hlášení odpovědět.
 */
@Service
@RequiredArgsConstructor
public class FeedbackService {

  private static final int MAX_FIRST = 50;
  private static final int MAX_OFFSET = 500;
  private static final Pattern CONTACT_EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

  private final FeedbackRepository feedbackRepository;
  private final AppUserRepository appUserRepository;
  private final FeedbackProperties feedbackProperties;
  private final FeedbackRateLimiter feedbackRateLimiter;
  private final EmailCipher emailCipher;
  private final HandleRenderer handleRenderer;

  @Transactional
  public FeedbackResult submit(FeedbackInput input, Long viewerUserId, ClientKind clientKind,
      String ipAddress, String platformInfo, String locale, String country) {
    if (!feedbackRateLimiter.tryAcquire(ipAddress, viewerUserId)) {
      throw new TooManyRequestsException();
    }

    String message = validateMessage(input.message());
    String contactEmail = validateContactEmail(input.contactEmail());

    Feedback feedback = Feedback.builder()
        .userId(viewerUserId)
        .category(input.category())
        .message(message)
        .contactEmailEnc(contactEmail == null ? null : emailCipher.encryptValue(contactEmail))
        .clientKind(clientKind)
        .appVersion(trimToLength(input.appVersion(), 30))
        .platformInfo(trimToLength(platformInfo, 200))
        .locale(trimToLength(locale, 10))
        .country(trimToLength(country, 2))
        .pageRef(trimToLength(input.pageRef(), 200))
        .diagnostics(trimToLength(input.diagnostics(), feedbackProperties.getMaxDiagnosticsLength()))
        .build();

    feedback = feedbackRepository.save(feedback);
    return new FeedbackResult(feedback.getId());
  }

  @Transactional(readOnly = true)
  public FeedbackItemResult list(Boolean handled, Integer first, Integer offset) {
    int limit = clamp(first == null ? 20 : first, 1, MAX_FIRST);
    int off = clamp(offset == null ? 0 : offset, 0, MAX_OFFSET);

    List<Feedback> page = feedbackRepository.findPage(handled, limit, off);
    long total = feedbackRepository.countPage(handled);

    List<FeedbackItem> items = page.stream().map(this::toItem).toList();
    return new FeedbackItemResult(items, (int) total, off + items.size() < total);
  }

  @Transactional
  public void setHandled(Long id, boolean handled, String note, Long moderatorUserId) {
    Feedback feedback = feedbackRepository.findById(id)
        .orElseThrow(() -> new NotFoundException(ErrorCode.FEEDBACK_NOT_FOUND));
    feedback.setHandledAt(handled ? OffsetDateTime.now() : null);
    feedback.setHandledByUserId(handled ? moderatorUserId : null);
    feedback.setHandledNote(handled ? trimToLength(note, 500) : null);
    feedbackRepository.save(feedback);
  }

  private FeedbackItem toItem(Feedback feedback) {
    AppUser author = feedback.getUserId() == null
        ? null
        : appUserRepository.findById(feedback.getUserId()).orElse(null);
    String contactEmail = feedback.getContactEmailEnc() == null
        ? null
        : emailCipher.decryptValue(feedback.getContactEmailEnc());
    return new FeedbackItem(feedback.getId(), feedback.getCategory(), feedback.getMessage(), contactEmail,
        feedback.getClientKind(), feedback.getAppVersion(), feedback.getPlatformInfo(), feedback.getLocale(),
        feedback.getCountry(), feedback.getPageRef(), feedback.getDiagnostics(), feedback.getCreatedAt(),
        feedback.getHandledAt() != null, feedback.getHandledNote(),
        author == null ? null : author.getPublicUid(), author == null ? null : handleRenderer.render(author));
  }

  private String validateMessage(String rawMessage) {
    if (rawMessage == null || rawMessage.isBlank()) {
      throw new ValidationException(ErrorCode.FEEDBACK_MESSAGE_REQUIRED);
    }
    String trimmed = rawMessage.trim();
    int max = feedbackProperties.getMaxMessageLength();
    if (trimmed.length() > max) {
      throw new ValidationException(ErrorCode.FEEDBACK_MESSAGE_TOO_LONG, max);
    }
    return trimmed;
  }

  private String validateContactEmail(String rawValue) {
    if (rawValue == null || rawValue.isBlank()) {
      return null;
    }
    String trimmed = rawValue.trim();
    if (!CONTACT_EMAIL_PATTERN.matcher(trimmed).matches()) {
      throw new ValidationException(ErrorCode.FEEDBACK_CONTACT_EMAIL_INVALID);
    }
    return trimmed;
  }

  private String trimToLength(String value, int maxLength) {
    if (value == null || value.isBlank()) return null;
    String trimmed = value.trim();
    return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
  }

  private int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
