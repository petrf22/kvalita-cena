package cz.kvalitacena.security;

import cz.kvalitacena.config.LegalProperties;
import cz.kvalitacena.config.OtpProperties;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.AppUserStatus;
import cz.kvalitacena.db.entity.ChallengePurpose;
import cz.kvalitacena.db.entity.ClientKind;
import cz.kvalitacena.db.entity.LoginChallenge;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.LoginChallengeRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.service.OtpMailSender;
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
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Souhlas s Podmínkami užití/Zásadami ochrany osobních údajů se zaznamenává PŘI REGISTRACI
 * (docs/soukromi.md), ne jen odkazem v UI patičce — appka ho tady vyžaduje na serveru, ne jen
 * checkboxem na klientovi, ať nejde JIT registraci obejít přímým voláním API. Existující účet
 * (přihlášení, ne registrace) souhlas nevyžaduje znovu.
 */
@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

  private static final byte[] EMAIL_HASH = {1, 1, 1};

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
  private HandleGenerator handleGenerator;
  @Mock
  private JwtService jwtService;
  @Mock
  private RefreshTokenService refreshTokenService;
  @Mock
  private OtpMailSender mailSender;

  private OtpProperties otpProperties;
  private LegalProperties legalProperties;
  private OtpService service;

  @BeforeEach
  void setUp() {
    otpProperties = new OtpProperties();
    otpProperties.setCodeTtl(Duration.ofMinutes(10));
    otpProperties.setResendCooldown(Duration.ofMinutes(1));
    otpProperties.setMaxAttempts(5);
    otpProperties.setMailEnabled(false); // DEV log větev

    legalProperties = new LegalProperties();
    legalProperties.setTermsVersion("3");

    service = new OtpService(challengeRepository, appUserRepository, emailCipher, codeEncoder,
        rateLimiter, otpProperties, handleGenerator, jwtService, refreshTokenService, mailSender,
        legalProperties);

    lenient().when(emailCipher.hash("uzivatel@example.com")).thenReturn(EMAIL_HASH);
    lenient().when(emailCipher.normalize(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  private LoginChallenge validChallenge() {
    return LoginChallenge.builder()
        .id(10L)
        .challengeUid(UUID.randomUUID())
        .emailHash(EMAIL_HASH)
        .codeHash("hash")
        .purpose(ChallengePurpose.LOGIN)
        .expiresAt(OffsetDateTime.now().plusMinutes(5))
        .maxAttempts((short) 5)
        .attempts((short) 0)
        .build();
  }

  private void stubValidCode(LoginChallenge challenge) {
    when(challengeRepository.findByChallengeUidAndConsumedAtIsNull(challenge.getChallengeUid()))
        .thenReturn(Optional.of(challenge));
    when(challengeRepository.incrementAttempts(challenge.getId())).thenReturn(1);
    when(codeEncoder.matches("123456", "hash")).thenReturn(true);
  }

  @Test
  void newUserWithoutConsentIsRejected() {
    LoginChallenge challenge = validChallenge();
    stubValidCode(challenge);
    when(appUserRepository.findByEmailHash(EMAIL_HASH)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.verifyOtp(challenge.getChallengeUid(), "123456",
        "uzivatel@example.com", null, ClientKind.WEB, "device"))
        .isInstanceOf(ValidationException.class);

    verify(appUserRepository, never()).save(any());
    verify(handleGenerator, never()).generateUnique();
  }

  @Test
  void newUserWithExplicitFalseConsentIsRejected() {
    LoginChallenge challenge = validChallenge();
    stubValidCode(challenge);
    when(appUserRepository.findByEmailHash(EMAIL_HASH)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.verifyOtp(challenge.getChallengeUid(), "123456",
        "uzivatel@example.com", false, ClientKind.WEB, "device"))
        .isInstanceOf(ValidationException.class);

    verify(appUserRepository, never()).save(any());
  }

  @Test
  void newUserWithConsentRecordsAcceptedVersionAndTimestamp() {
    LoginChallenge challenge = validChallenge();
    stubValidCode(challenge);
    when(appUserRepository.findByEmailHash(EMAIL_HASH)).thenReturn(Optional.empty());
    when(handleGenerator.generateUnique()).thenReturn(
        new HandleGenerator.GeneratedHandle("modry", new HandleGenerator.HandleNoun("stork", HandleGenerator.Gender.M), 42));
    when(emailCipher.encrypt("uzivatel@example.com")).thenReturn(new byte[]{9});
    when(emailCipher.extractDomain("uzivatel@example.com")).thenReturn("example.com");
    when(appUserRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(jwtService.issueAccessToken(any())).thenReturn("access-token");
    when(refreshTokenService.issueNewFamily(any(), any(), any()))
        .thenReturn(new RefreshTokenService.IssuedToken("refresh-token", null));

    OtpService.AuthResult result = service.verifyOtp(challenge.getChallengeUid(), "123456",
        "uzivatel@example.com", true, ClientKind.WEB, "device");

    assertThat(result.newUser()).isTrue();
    var userCaptor = org.mockito.ArgumentCaptor.forClass(AppUser.class);
    verify(appUserRepository).save(userCaptor.capture());
    assertThat(userCaptor.getValue().getTermsAcceptedAt()).isNotNull();
    assertThat(userCaptor.getValue().getTermsVersion()).isEqualTo("3");
  }

  @Test
  void requestOtpRejectsSuspendedAccount() {
    when(rateLimiter.tryAcquireForRequest(any(), any())).thenReturn(true);
    when(appUserRepository.findByEmailHash(EMAIL_HASH))
        .thenReturn(Optional.of(AppUser.builder().id(5L).status(AppUserStatus.SUSPENDED).build()));

    ValidationException error = catchThrowableOfType(ValidationException.class,
        () -> service.requestOtp("uzivatel@example.com", ClientKind.WEB, "1.2.3.4"));

    assertThat(error.getCode()).isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
    verify(challengeRepository, never()).save(any());
  }

  @Test
  void verifyOtpRejectsSuspendedAccount() {
    LoginChallenge challenge = validChallenge();
    stubValidCode(challenge);
    when(appUserRepository.findByEmailHash(EMAIL_HASH))
        .thenReturn(Optional.of(AppUser.builder().id(5L).status(AppUserStatus.SUSPENDED).build()));

    ValidationException error = catchThrowableOfType(ValidationException.class,
        () -> service.verifyOtp(challenge.getChallengeUid(), "123456", "uzivatel@example.com", null,
            ClientKind.WEB, "device"));

    assertThat(error.getCode()).isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
    verify(appUserRepository, never()).save(any());
  }

  @Test
  void existingUserLoginDoesNotRequireConsent() {
    LoginChallenge challenge = validChallenge();
    stubValidCode(challenge);
    AppUser existing = AppUser.builder().id(5L).emailHash(EMAIL_HASH).tokenVersion(0).build();
    when(appUserRepository.findByEmailHash(EMAIL_HASH)).thenReturn(Optional.of(existing));
    when(appUserRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(jwtService.issueAccessToken(any())).thenReturn("access-token");
    when(refreshTokenService.issueNewFamily(any(), any(), any()))
        .thenReturn(new RefreshTokenService.IssuedToken("refresh-token", null));

    OtpService.AuthResult result = service.verifyOtp(challenge.getChallengeUid(), "123456",
        "uzivatel@example.com", null, ClientKind.WEB, "device");

    assertThat(result.newUser()).isFalse();
    verify(handleGenerator, never()).generateUnique();
    assertThat(existing.getTermsAcceptedAt()).isNull();
  }
}
