package cz.kvalitacena.service;

import cz.kvalitacena.config.OtpProperties;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.ChallengePurpose;
import cz.kvalitacena.db.entity.ClientKind;
import cz.kvalitacena.db.entity.LoginChallenge;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.LoginChallengeRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.TooManyRequestsException;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.EmailCipher;
import cz.kvalitacena.security.OtpRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * Změna přihlašovacího e-mailu — VLASTNÍ tok vedle přihlašovacího OTP (viz {@link OtpService}),
 * ne pole ve formuláři profilu: kód jde vždy na NOVOU adresu, jinak by si uživatel překlepem
 * zamkl účet (docs/soukromi.md, "Profil uživatele a viditelnost").
 *
 * <p>{@link #requestChange} vrací STEJNOU odpověď bez ohledu na to, jestli je nová adresa volná
 * nebo už patří jinému účtu — enumerace účtů zůstává nemožná stejně jako u {@link OtpService}.
 * Liší se jen OBSAH e-mailu (kód vs. upozornění), a tu informaci tak dostane jen ten, kdo do
 * dané schránky skutečně vidí. Skutečná pojistka proti hijacku cizí adresy je až v
 * {@link #confirmChange} — response na request kroku je schválně nerozlišitelná, ale confirm
 * cizí e-mail nikdy nepřevezme.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailChangeService {

  private final LoginChallengeRepository challengeRepository;
  private final AppUserRepository appUserRepository;
  private final EmailCipher emailCipher;
  private final PasswordEncoder codeEncoder; // Argon2id, stejný bean jako OtpService
  private final OtpRateLimiter rateLimiter;
  private final OtpProperties properties;
  private final OtpMailSender mailSender;
  private final SecureRandom secureRandom = new SecureRandom();

  public record RequestResult(UUID challengeUid, long expiresInSec, long resendAfterSec) {
  }

  @Transactional
  public RequestResult requestChange(AppUser currentUser, String newRawEmail, ClientKind clientKind, String ipAddress) {
    byte[] newEmailHash = emailCipher.hash(newRawEmail);
    if (Arrays.equals(newEmailHash, currentUser.getEmailHash())) {
      throw new ValidationException(ErrorCode.EMAIL_CHANGE_SAME_ADDRESS);
    }
    if (!rateLimiter.tryAcquireForRequest(newEmailHash, ipAddress)) {
      throw new TooManyRequestsException();
    }

    // Stejná pojistka jako u přihlašovacího OTP — na adresu smí být aktivní nejvýš jedna výzva.
    challengeRepository.invalidateActiveChallenges(newEmailHash, OffsetDateTime.now());

    String code = generateSixDigitCode();
    LoginChallenge challenge = LoginChallenge.builder()
        .emailHash(newEmailHash)
        .codeHash(codeEncoder.encode(code))
        .purpose(ChallengePurpose.EMAIL_CHANGE)
        .expiresAt(OffsetDateTime.now().plus(properties.getCodeTtl()))
        .maxAttempts((short) properties.getMaxAttempts())
        .ipHash(ipAddress == null ? null : emailCipher.hash(ipAddress))
        .clientKind(clientKind)
        .build();
    challenge = challengeRepository.save(challenge);

    boolean alreadyTaken = appUserRepository.findByEmailHash(newEmailHash)
        .filter(existing -> !existing.getId().equals(currentUser.getId()))
        .isPresent();

    Locale locale = currentUser.getLocale() != null && !currentUser.getLocale().isBlank()
        ? Locale.forLanguageTag(currentUser.getLocale())
        : LocaleContextHolder.getLocale();

    if (properties.isMailEnabled()) {
      if (alreadyTaken) {
        mailSender.sendEmailChangeConflict(newRawEmail, locale);
      } else {
        mailSender.sendEmailChangeCode(newRawEmail, code, locale);
      }
    } else {
      log.info("[DEV] Kód pro změnu e-mailu na {} (challenge {}, adresa obsazená: {}): {}",
          emailCipher.normalize(newRawEmail), challenge.getChallengeUid(), alreadyTaken, code);
    }

    return new RequestResult(challenge.getChallengeUid(), properties.getCodeTtl().toSeconds(),
        properties.getResendCooldown().toSeconds());
  }

  @Transactional
  public void confirmChange(AppUser currentUser, UUID challengeUid, String code, String newRawEmail) {
    LoginChallenge challenge = challengeRepository.findByChallengeUidAndConsumedAtIsNull(challengeUid)
        .filter(c -> c.getPurpose() == ChallengePurpose.EMAIL_CHANGE)
        .orElseThrow(() -> new ValidationException(ErrorCode.EMAIL_CHANGE_INVALID_CHALLENGE));

    byte[] newEmailHash = emailCipher.hash(newRawEmail);
    if (!Arrays.equals(newEmailHash, challenge.getEmailHash())) {
      throw new ValidationException(ErrorCode.EMAIL_CHANGE_INVALID_CHALLENGE);
    }
    if (challenge.getExpiresAt().isBefore(OffsetDateTime.now())) {
      throw new ValidationException(ErrorCode.EMAIL_CHANGE_INVALID_CHALLENGE);
    }

    // Atomický inkrement — stejná pojistka proti souběžnému vyzkoušení víc pokusů jako u OtpService.
    int updated = challengeRepository.incrementAttempts(challenge.getId());
    if (updated == 0) {
      challenge.setConsumedAt(OffsetDateTime.now());
      challengeRepository.save(challenge);
      throw new ValidationException(ErrorCode.EMAIL_CHANGE_INVALID_CHALLENGE);
    }
    if (!codeEncoder.matches(code, challenge.getCodeHash())) {
      throw new ValidationException(ErrorCode.EMAIL_CHANGE_INVALID_CHALLENGE);
    }
    challenge.setConsumedAt(OffsetDateTime.now());
    challengeRepository.save(challenge);

    // Skutečná pojistka proti převzetí cizí adresy — odpověď na request kroku byla schválně
    // nerozlišitelná (viz javadoc třídy), tady se to musí ověřit natvrdo.
    boolean takenByOther = appUserRepository.findByEmailHash(newEmailHash)
        .filter(existing -> !existing.getId().equals(currentUser.getId()))
        .isPresent();
    if (takenByOther) {
      throw new ValidationException(ErrorCode.EMAIL_CHANGE_EMAIL_TAKEN);
    }

    currentUser.setEmailHash(newEmailHash);
    currentUser.setEmailEnc(emailCipher.encrypt(newRawEmail));
    currentUser.setEmailDomain(emailCipher.extractDomain(newRawEmail));
    // Odhlásí ostatní zařízení — stejný mechanismus jako "podezření na krádež" (docs/soukromi.md).
    currentUser.setTokenVersion(currentUser.getTokenVersion() + 1);
    appUserRepository.save(currentUser);
  }

  private String generateSixDigitCode() {
    int value = 100_000 + secureRandom.nextInt(900_000);
    return Integer.toString(value);
  }
}
