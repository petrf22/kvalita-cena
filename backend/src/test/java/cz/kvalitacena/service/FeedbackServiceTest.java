package cz.kvalitacena.service;

import cz.kvalitacena.config.FeedbackProperties;
import cz.kvalitacena.controller.FeedbackInput;
import cz.kvalitacena.controller.FeedbackItemResult;
import cz.kvalitacena.controller.FeedbackResult;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.ClientKind;
import cz.kvalitacena.db.entity.Feedback;
import cz.kvalitacena.db.entity.FeedbackCategory;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.FeedbackRepository;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.TooManyRequestsException;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.EmailCipher;
import cz.kvalitacena.security.FeedbackRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Zpětná vazba od uživatele appky (core.feedback) — na rozdíl od nahlašování (RecordFlagService)
 * funguje i anonymně, viz docs/soukromi.md.
 */
@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

  private static final Long USER_ID = 42L;
  private static final UUID PUBLIC_UID = UUID.randomUUID();
  private static final String IP = "203.0.113.5";

  @Mock
  private FeedbackRepository feedbackRepository;
  @Mock
  private AppUserRepository appUserRepository;
  @Mock
  private FeedbackRateLimiter feedbackRateLimiter;
  @Mock
  private EmailCipher emailCipher;
  @Mock
  private HandleRenderer handleRenderer;

  private final FeedbackProperties feedbackProperties = new FeedbackProperties();

  private FeedbackService service() {
    feedbackProperties.setMaxMessageLength(2000);
    feedbackProperties.setMaxDiagnosticsLength(8000);
    feedbackProperties.setMaxPerDayPerIp(20);
    feedbackProperties.setMaxPerDayPerUser(20);
    return new FeedbackService(feedbackRepository, appUserRepository, feedbackProperties,
        feedbackRateLimiter, emailCipher, handleRenderer);
  }

  private FeedbackInput input(String message, String contactEmail) {
    return new FeedbackInput(FeedbackCategory.BUG, message, contactEmail, "/product/7", "0.1.0", null);
  }

  @Test
  void anonymousSubmissionIsAllowed() {
    when(feedbackRateLimiter.tryAcquire(IP, null)).thenReturn(true);
    when(feedbackRepository.save(any())).thenAnswer(inv -> {
      Feedback f = inv.getArgument(0);
      f.setId(1L);
      return f;
    });

    FeedbackResult result = service().submit(input("appka mi spadla", null), null, ClientKind.ANDROID,
        IP, "Pixel 6, API 30", "cs", "CZ");

    assertThat(result.id()).isEqualTo(1L);
    ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
    org.mockito.Mockito.verify(feedbackRepository).save(captor.capture());
    assertThat(captor.getValue().getUserId()).isNull();
    assertThat(captor.getValue().getClientKind()).isEqualTo(ClientKind.ANDROID);
  }

  @Test
  void emptyMessageIsRejected() {
    when(feedbackRateLimiter.tryAcquire(any(), any())).thenReturn(true);
    assertThatThrownBy(() -> service().submit(input("   ", null), USER_ID, ClientKind.WEB, IP, null, "cs", "CZ"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void tooLongMessageIsRejected() {
    when(feedbackRateLimiter.tryAcquire(any(), any())).thenReturn(true);
    feedbackProperties.setMaxMessageLength(10);
    FeedbackService service = new FeedbackService(feedbackRepository, appUserRepository, feedbackProperties,
        feedbackRateLimiter, emailCipher, handleRenderer);
    assertThatThrownBy(() -> service.submit(input("tohle je moc dlouhá zpráva", null), USER_ID, ClientKind.WEB,
        IP, null, "cs", "CZ"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void invalidContactEmailIsRejected() {
    when(feedbackRateLimiter.tryAcquire(any(), any())).thenReturn(true);
    assertThatThrownBy(() -> service().submit(input("test", "not-an-email"), USER_ID, ClientKind.WEB,
        IP, null, "cs", "CZ"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void contactEmailIsEncryptedBeforeSaving() {
    when(feedbackRateLimiter.tryAcquire(any(), any())).thenReturn(true);
    when(emailCipher.encryptValue("kontakt@example.com")).thenReturn(new byte[]{1, 2, 3});
    when(feedbackRepository.save(any())).thenAnswer(inv -> {
      Feedback f = inv.getArgument(0);
      f.setId(9L);
      return f;
    });

    service().submit(input("test", "kontakt@example.com"), USER_ID, ClientKind.WEB, IP, null, "cs", "CZ");

    ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
    org.mockito.Mockito.verify(feedbackRepository).save(captor.capture());
    assertThat(captor.getValue().getContactEmailEnc()).isEqualTo(new byte[]{1, 2, 3});
  }

  @Test
  void rateLimitedIpIsRejected() {
    when(feedbackRateLimiter.tryAcquire(IP, null)).thenReturn(false);
    assertThatThrownBy(() -> service().submit(input("test", null), null, ClientKind.WEB, IP, null, "cs", "CZ"))
        .isInstanceOf(TooManyRequestsException.class);
  }

  @Test
  void listReturnsAuthorForLoggedInSubmitter() {
    Feedback feedback = Feedback.builder().id(5L).userId(USER_ID).category(FeedbackCategory.IDEA)
        .message("nápad").clientKind(ClientKind.WEB).build();
    when(feedbackRepository.findPage(null, 20, 0)).thenReturn(List.of(feedback));
    when(feedbackRepository.countPage(null)).thenReturn(1L);
    AppUser author = AppUser.builder().id(USER_ID).publicUid(PUBLIC_UID).build();
    when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(author));
    when(handleRenderer.render(author)).thenReturn("Modrý čáp #4271");

    FeedbackItemResult result = service().list(null, null, null);

    assertThat(result.items()).hasSize(1);
    assertThat(result.items().get(0).authorPublicUid()).isEqualTo(PUBLIC_UID);
    assertThat(result.items().get(0).authorHandle()).isEqualTo("Modrý čáp #4271");
  }

  @Test
  void listReturnsNullAuthorForAnonymousSubmission() {
    Feedback feedback = Feedback.builder().id(6L).userId(null).category(FeedbackCategory.OTHER)
        .message("anonym").clientKind(ClientKind.WEB).build();
    when(feedbackRepository.findPage(null, 20, 0)).thenReturn(List.of(feedback));
    when(feedbackRepository.countPage(null)).thenReturn(1L);

    FeedbackItemResult result = service().list(null, null, null);

    assertThat(result.items().get(0).authorPublicUid()).isNull();
  }

  @Test
  void settingHandledOnMissingFeedbackThrowsNotFound() {
    when(feedbackRepository.findById(999L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service().setHandled(999L, true, "vyřešeno", USER_ID))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void settingHandledStampsModeratorAndClearsOnUnset() {
    Feedback feedback = Feedback.builder().id(7L).category(FeedbackCategory.BUG).message("x")
        .clientKind(ClientKind.WEB).build();
    when(feedbackRepository.findById(7L)).thenReturn(Optional.of(feedback));
    when(feedbackRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service().setHandled(7L, true, "vyřešeno", USER_ID);

    ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
    org.mockito.Mockito.verify(feedbackRepository).save(captor.capture());
    assertThat(captor.getValue().getHandledAt()).isNotNull();
    assertThat(captor.getValue().getHandledByUserId()).isEqualTo(USER_ID);
    assertThat(captor.getValue().getHandledNote()).isEqualTo("vyřešeno");
  }
}
