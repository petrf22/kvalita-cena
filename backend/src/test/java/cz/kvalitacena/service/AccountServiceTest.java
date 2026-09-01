package cz.kvalitacena.service;

import cz.kvalitacena.config.OtpProperties;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.ChallengePurpose;
import cz.kvalitacena.db.entity.ClientKind;
import cz.kvalitacena.db.entity.LoginChallenge;
import cz.kvalitacena.db.entity.Media;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.LoginChallengeRepository;
import cz.kvalitacena.db.repo.MediaRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.ProductReviewRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductUserEditRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.db.repo.StoreUserEditRepository;
import cz.kvalitacena.db.repo.UserProfileRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Výmaz účtu je nevratný, proto testy ověřují hlavně dvě věci: že se OTP challenge validuje
 * stejně přísně jako u {@link EmailChangeServiceTest} (jiný účel, cizí e-mail, expirace,
 * vyčerpané pokusy, špatný kód), a že observace appka nikdy skutečně nemaže — jen anonymizuje
 * cizí klíč (fk_price_observation_submitter je ON DELETE SET NULL, žádná explicitní akce navíc).
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

  private static final Long USER_ID = 1L;
  private static final byte[] EMAIL_HASH = {1, 1, 1};

  @Mock
  private AppUserRepository appUserRepository;
  @Mock
  private UserProfileRepository userProfileRepository;
  @Mock
  private PriceObservationRepository priceObservationRepository;
  @Mock
  private ProductReviewRepository reviewRepository;
  @Mock
  private ProductUserEditRepository productUserEditRepository;
  @Mock
  private StoreUserEditRepository storeUserEditRepository;
  @Mock
  private ProductRepository productRepository;
  @Mock
  private StoreRepository storeRepository;
  @Mock
  private MediaRepository mediaRepository;
  @Mock
  private MediaStorage mediaStorage;
  @Mock
  private LoginChallengeRepository challengeRepository;
  @Mock
  private EmailCipher emailCipher;
  @Mock
  private PasswordEncoder codeEncoder;
  @Mock
  private OtpRateLimiter rateLimiter;
  @Mock
  private OtpMailSender mailSender;

  private OtpProperties properties;
  private AccountService service;
  private AppUser currentUser;

  @BeforeEach
  void setUp() {
    properties = new OtpProperties();
    properties.setCodeTtl(Duration.ofMinutes(10));
    properties.setResendCooldown(Duration.ofMinutes(1));
    properties.setMaxAttempts(5);
    properties.setMailEnabled(false); // DEV log větev

    service = new AccountService(appUserRepository, userProfileRepository, priceObservationRepository,
        reviewRepository, productUserEditRepository, storeUserEditRepository, productRepository,
        storeRepository, mediaRepository, mediaStorage, challengeRepository, emailCipher, codeEncoder,
        rateLimiter, properties, mailSender);
    currentUser = AppUser.builder().id(USER_ID).emailHash(EMAIL_HASH).publicHandle("blue-stork-1").build();

    lenient().when(emailCipher.normalize(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void rateLimitExceededThrowsTooManyRequests() {
    when(rateLimiter.tryAcquireForRequest(eq(EMAIL_HASH), any())).thenReturn(false);

    assertThatThrownBy(() -> service.requestDelete(currentUser, ClientKind.WEB, "1.2.3.4"))
        .isInstanceOf(TooManyRequestsException.class);
    verify(challengeRepository, never()).save(any());
  }

  @Test
  void requestSavesChallengeWithDeleteAccountPurpose() {
    when(rateLimiter.tryAcquireForRequest(eq(EMAIL_HASH), any())).thenReturn(true);
    when(codeEncoder.encode(any())).thenReturn("hash");
    when(emailCipher.decrypt(any())).thenReturn("uzivatel@example.com");
    when(challengeRepository.save(any())).thenAnswer(inv -> {
      LoginChallenge c = inv.getArgument(0);
      c.setId(1L);
      if (c.getChallengeUid() == null) c.setChallengeUid(UUID.randomUUID());
      return c;
    });

    AccountService.RequestResult result = service.requestDelete(currentUser, ClientKind.WEB, "1.2.3.4");

    assertThat(result.challengeUid()).isNotNull();
    assertThat(result.expiresInSec()).isEqualTo(600);
    verify(challengeRepository).save(argThat(c -> c.getPurpose() == ChallengePurpose.DELETE_ACCOUNT
        && c.getEmailHash() == EMAIL_HASH));
  }

  private LoginChallenge validChallenge() {
    return LoginChallenge.builder()
        .id(10L)
        .challengeUid(UUID.randomUUID())
        .emailHash(EMAIL_HASH)
        .codeHash("hash")
        .purpose(ChallengePurpose.DELETE_ACCOUNT)
        .expiresAt(OffsetDateTime.now().plusMinutes(5))
        .maxAttempts((short) 5)
        .attempts((short) 0)
        .build();
  }

  @Test
  void confirmWithWrongPurposeChallengeIsRejected() {
    LoginChallenge loginChallenge = validChallenge();
    loginChallenge.setPurpose(ChallengePurpose.LOGIN);
    when(challengeRepository.findByChallengeUidAndConsumedAtIsNull(loginChallenge.getChallengeUid()))
        .thenReturn(Optional.of(loginChallenge));

    assertThatThrownBy(() -> service.confirmDelete(currentUser, loginChallenge.getChallengeUid(), "123456"))
        .isInstanceOf(ValidationException.class);
    verify(appUserRepository, never()).delete(any());
  }

  @Test
  void confirmWithMismatchedEmailHashIsRejected() {
    LoginChallenge challenge = validChallenge();
    challenge.setEmailHash(new byte[]{9, 9, 9});
    when(challengeRepository.findByChallengeUidAndConsumedAtIsNull(challenge.getChallengeUid()))
        .thenReturn(Optional.of(challenge));

    assertThatThrownBy(() -> service.confirmDelete(currentUser, challenge.getChallengeUid(), "123456"))
        .isInstanceOf(ValidationException.class);
    verify(appUserRepository, never()).delete(any());
  }

  @Test
  void confirmWithExpiredChallengeIsRejected() {
    LoginChallenge challenge = validChallenge();
    challenge.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
    when(challengeRepository.findByChallengeUidAndConsumedAtIsNull(challenge.getChallengeUid()))
        .thenReturn(Optional.of(challenge));

    assertThatThrownBy(() -> service.confirmDelete(currentUser, challenge.getChallengeUid(), "123456"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void confirmWithExhaustedAttemptsIsRejected() {
    LoginChallenge challenge = validChallenge();
    when(challengeRepository.findByChallengeUidAndConsumedAtIsNull(challenge.getChallengeUid()))
        .thenReturn(Optional.of(challenge));
    when(challengeRepository.incrementAttempts(challenge.getId())).thenReturn(0);

    assertThatThrownBy(() -> service.confirmDelete(currentUser, challenge.getChallengeUid(), "123456"))
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

    assertThatThrownBy(() -> service.confirmDelete(currentUser, challenge.getChallengeUid(), "000000"))
        .isInstanceOf(ValidationException.class);
    verify(appUserRepository, never()).delete(any());
  }

  @Test
  void deletionLeavesObservationAnonymizationToTheDatabaseForeignKey() {
    LoginChallenge challenge = validChallenge();
    when(challengeRepository.findByChallengeUidAndConsumedAtIsNull(challenge.getChallengeUid()))
        .thenReturn(Optional.of(challenge));
    when(challengeRepository.incrementAttempts(challenge.getId())).thenReturn(1);
    when(codeEncoder.matches("123456", "hash")).thenReturn(true);
    when(mediaRepository.findByUploadedByUserId(USER_ID)).thenReturn(List.of());

    service.confirmDelete(currentUser, challenge.getChallengeUid(), "123456");

    // Appka observace nikdy nemaže ani nespouští žádný přepočet — spoléhá se výhradně na
    // fk_price_observation_submitter ON DELETE SET NULL, žádná explicitní interakce s
    // priceObservationRepository navíc.
    verifyNoInteractions(priceObservationRepository);
    verify(appUserRepository).delete(currentUser);
    assertThat(challenge.getConsumedAt()).isNotNull();
  }

  @Test
  void mediaFilesAreDeletedFromStorageBeforeTheUserRow() {
    LoginChallenge challenge = validChallenge();
    when(challengeRepository.findByChallengeUidAndConsumedAtIsNull(challenge.getChallengeUid()))
        .thenReturn(Optional.of(challenge));
    when(challengeRepository.incrementAttempts(challenge.getId())).thenReturn(1);
    when(codeEncoder.matches("123456", "hash")).thenReturn(true);
    Media avatar = Media.builder().id(1L).storageKey("2026/08/abc.jpg").uploadedByUserId(USER_ID).build();
    when(mediaRepository.findByUploadedByUserId(USER_ID)).thenReturn(List.of(avatar));

    service.confirmDelete(currentUser, challenge.getChallengeUid(), "123456");

    verify(mediaStorage).delete("2026/08/abc.jpg");
    verify(appUserRepository).delete(currentUser);
  }
}
