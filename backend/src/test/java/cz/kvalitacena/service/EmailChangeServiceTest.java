package cz.kvalitacena.service;

import cz.kvalitacena.config.OtpProperties;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.ChallengePurpose;
import cz.kvalitacena.db.entity.ClientKind;
import cz.kvalitacena.db.entity.LoginChallenge;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.LoginChallengeRepository;
import cz.kvalitacena.exception.TooManyRequestsException;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.EmailCipher;
import cz.kvalitacena.security.OtpRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Změna přihlašovacího e-mailu je VLASTNÍ tok, ne pole ve formuláři profilu (docs/soukromi.md) —
 * testy ověřují dvě vrstvy pojistek: request krok nerozlišuje volnou/obsazenou adresu navenek
 * (jen obsah e-mailu se liší), confirm krok pak cizí adresu nikdy skutečně nepřevezme, i kdyby
 * někdo uhodl kód pro cizí challenge.
 */
@ExtendWith(MockitoExtension.class)
class EmailChangeServiceTest {

  private static final Long USER_ID = 1L;
  private static final byte[] CURRENT_EMAIL_HASH = {1, 1, 1};
  private static final byte[] NEW_EMAIL_HASH = {2, 2, 2};

  @Mock
  private LoginChallengeRepository challengeRepository;
  @Mock
  private AppUserRepository appUserRepository;
  @Mock
  private EmailCipher emailCipher;
  @Mock
  private PasswordEncoder codeEncoder;
  @Mock
  private OtpRateLimiter rateLimiter;
  @Mock
  private OtpMailSender mailSender;

  private OtpProperties properties;
  private EmailChangeService service;
  private AppUser currentUser;

  @BeforeEach
  void setUp() {
    properties = new OtpProperties();
    properties.setCodeTtl(Duration.ofMinutes(10));
    properties.setResendCooldown(Duration.ofMinutes(1));
    properties.setMaxAttempts(5);
    properties.setMailEnabled(false); // DEV log větev — testy se soustředí na logiku, ne na SMTP

    service = new EmailChangeService(
        challengeRepository, appUserRepository, emailCipher, codeEncoder, rateLimiter, properties, mailSender);
    currentUser = AppUser.builder().id(USER_ID).emailHash(CURRENT_EMAIL_HASH).tokenVersion(0).build();

    lenient().when(emailCipher.hash("nova@example.com")).thenReturn(NEW_EMAIL_HASH);
    lenient().when(emailCipher.normalize(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void requestingSameAddressAsCurrentEmailIsRejectedUpfront() {
    when(emailCipher.hash("stara@example.com")).thenReturn(CURRENT_EMAIL_HASH);

    assertThatThrownBy(() -> service.requestChange(currentUser, "stara@example.com", ClientKind.WEB, "1.2.3.4"))
        .isInstanceOf(ValidationException.class);
    verify(challengeRepository, never()).save(any());
  }

  @Test
  void rateLimitExceededThrowsTooManyRequests() {
    when(rateLimiter.tryAcquireForRequest(eq(NEW_EMAIL_HASH), any())).thenReturn(false);

    assertThatThrownBy(() -> service.requestChange(currentUser, "nova@example.com", ClientKind.WEB, "1.2.3.4"))
        .isInstanceOf(TooManyRequestsException.class);
  }

  @Test
  void requestReturnsSameShapeWhetherAddressIsFreeOrTaken() {
    when(rateLimiter.tryAcquireForRequest(eq(NEW_EMAIL_HASH), any())).thenReturn(true);
    when(codeEncoder.encode(any())).thenReturn("hash");
    when(challengeRepository.save(any())).thenAnswer(inv -> {
      LoginChallenge c = inv.getArgument(0);
      c.setId(1L);
      if (c.getChallengeUid() == null) c.setChallengeUid(UUID.randomUUID());
      return c;
    });
    // Adresa je volná.
    when(appUserRepository.findByEmailHash(NEW_EMAIL_HASH)).thenReturn(Optional.empty());

    EmailChangeService.RequestResult free =
        service.requestChange(currentUser, "nova@example.com", ClientKind.WEB, "1.2.3.4");
    assertThat(free.challengeUid()).isNotNull();
    assertThat(free.expiresInSec()).isEqualTo(600);

    // Adresa patří jinému účtu — odpověď má STEJNÝ tvar, jen se pošle jiný e-mail (mailEnabled
    // je tu false, ověřuje se to v DEV log větvi implicitně tím, že nic nevyhodí).
    when(appUserRepository.findByEmailHash(NEW_EMAIL_HASH))
        .thenReturn(Optional.of(AppUser.builder().id(999L).build()));

    EmailChangeService.RequestResult taken =
        service.requestChange(currentUser, "nova@example.com", ClientKind.WEB, "1.2.3.4");
    assertThat(taken.expiresInSec()).isEqualTo(free.expiresInSec());
  }

  private LoginChallenge validChallenge() {
    return LoginChallenge.builder()
        .id(10L)
        .challengeUid(UUID.randomUUID())
        .emailHash(NEW_EMAIL_HASH)
        .codeHash("hash")
        .purpose(ChallengePurpose.EMAIL_CHANGE)
        .expiresAt(OffsetDateTime.now().plusMinutes(5))
        .maxAttempts((short) 5)
        .attempts((short) 0)
        .build();
  }

  @Test
  void confirmWithLoginPurposeChallengeIsRejected() {
    LoginChallenge loginChallenge = validChallenge();
    loginChallenge.setPurpose(ChallengePurpose.LOGIN);
    when(challengeRepository.findByChallengeUidAndConsumedAtIsNull(loginChallenge.getChallengeUid()))
        .thenReturn(Optional.of(loginChallenge));

    assertThatThrownBy(() -> service.confirmChange(currentUser, loginChallenge.getChallengeUid(), "123456", "nova@example.com"))
        .isInstanceOf(ValidationException.class);
    verify(codeEncoder, never()).matches(any(), any());
  }

  @Test
  void confirmWithMismatchedEmailIsRejected() {
    LoginChallenge challenge = validChallenge();
    when(challengeRepository.findByChallengeUidAndConsumedAtIsNull(challenge.getChallengeUid()))
        .thenReturn(Optional.of(challenge));
    when(emailCipher.hash("jina@example.com")).thenReturn(new byte[]{9, 9, 9});

    assertThatThrownBy(() -> service.confirmChange(currentUser, challenge.getChallengeUid(), "123456", "jina@example.com"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void confirmWithExpiredChallengeIsRejected() {
    LoginChallenge challenge = validChallenge();
    challenge.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
    when(challengeRepository.findByChallengeUidAndConsumedAtIsNull(challenge.getChallengeUid()))
        .thenReturn(Optional.of(challenge));

    assertThatThrownBy(() -> service.confirmChange(currentUser, challenge.getChallengeUid(), "123456", "nova@example.com"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void confirmWithExhaustedAttemptsIsRejected() {
    LoginChallenge challenge = validChallenge();
    when(challengeRepository.findByChallengeUidAndConsumedAtIsNull(challenge.getChallengeUid()))
        .thenReturn(Optional.of(challenge));
    when(challengeRepository.incrementAttempts(challenge.getId())).thenReturn(0);

    assertThatThrownBy(() -> service.confirmChange(currentUser, challenge.getChallengeUid(), "123456", "nova@example.com"))
        .isInstanceOf(ValidationException.class);
    verify(codeEncoder, never()).matches(any(), any());
  }

  @Test
  void confirmWithWrongCodeIsRejected() {
    LoginChallenge challenge = validChallenge();
    when(challengeRepository.findByChallengeUidAndConsumedAtIsNull(challenge.getChallengeUid()))
        .thenReturn(Optional.of(challenge));
    when(challengeRepository.incrementAttempts(challenge.getId())).thenReturn(1);
    when(codeEncoder.matches("000000", "hash")).thenReturn(false);

    assertThatThrownBy(() -> service.confirmChange(currentUser, challenge.getChallengeUid(), "000000", "nova@example.com"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void confirmRejectsWhenNewEmailBelongsToAnotherAccountEvenWithCorrectCode() {
    LoginChallenge challenge = validChallenge();
    when(challengeRepository.findByChallengeUidAndConsumedAtIsNull(challenge.getChallengeUid()))
        .thenReturn(Optional.of(challenge));
    when(challengeRepository.incrementAttempts(challenge.getId())).thenReturn(1);
    when(codeEncoder.matches("123456", "hash")).thenReturn(true);
    when(appUserRepository.findByEmailHash(NEW_EMAIL_HASH))
        .thenReturn(Optional.of(AppUser.builder().id(999L).build()));

    assertThatThrownBy(() -> service.confirmChange(currentUser, challenge.getChallengeUid(), "123456", "nova@example.com"))
        .isInstanceOf(ValidationException.class);
    verify(appUserRepository, never()).save(argThat(u -> u == currentUser && u.getTokenVersion() > 0));
  }

  @Test
  void confirmSucceedsAndIncrementsTokenVersionToSignOutOtherDevices() {
    LoginChallenge challenge = validChallenge();
    when(challengeRepository.findByChallengeUidAndConsumedAtIsNull(challenge.getChallengeUid()))
        .thenReturn(Optional.of(challenge));
    when(challengeRepository.incrementAttempts(challenge.getId())).thenReturn(1);
    when(codeEncoder.matches("123456", "hash")).thenReturn(true);
    when(appUserRepository.findByEmailHash(NEW_EMAIL_HASH)).thenReturn(Optional.empty());
    when(emailCipher.encrypt("nova@example.com")).thenReturn(new byte[]{7});
    when(emailCipher.extractDomain("nova@example.com")).thenReturn("example.com");

    service.confirmChange(currentUser, challenge.getChallengeUid(), "123456", "nova@example.com");

    assertThat(currentUser.getEmailHash()).isEqualTo(NEW_EMAIL_HASH);
    assertThat(currentUser.getEmailDomain()).isEqualTo("example.com");
    assertThat(currentUser.getTokenVersion()).isEqualTo(1);
    verify(appUserRepository).save(currentUser);
    assertThat(challenge.getConsumedAt()).isNotNull();
  }
}
