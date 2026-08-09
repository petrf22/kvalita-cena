package cz.kvalitacena.security;

import cz.kvalitacena.config.OtpProperties;
import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.LoginChallengeRepository;
import cz.kvalitacena.exception.InvalidChallengeException;
import cz.kvalitacena.exception.TooManyRequestsException;
import cz.kvalitacena.service.OtpMailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Passwordless přihlášení: e-mail → OTP kód → token. Viz docs/soukromi.md (§ Passwordless auth)
 * a plán projektu.
 *
 * <p>Request krok NEROZLIŠUJE existující/neexistující e-mail (účet vzniká JIT až při úspěšném
 * ověření) — chování je pro oba případy identické, takže nejde postupně zjišťovat, kdo je
 * zaregistrovaný (žádná enumerace účtů).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

  private final LoginChallengeRepository challengeRepository;
  private final AppUserRepository appUserRepository;
  private final EmailCipher emailCipher;
  private final PasswordEncoder codeEncoder; // Argon2id, viz SecurityConfig
  private final OtpRateLimiter rateLimiter;
  private final OtpProperties properties;
  private final HandleGenerator handleGenerator;
  private final JwtService jwtService;
  private final RefreshTokenService refreshTokenService;
  private final OtpMailSender mailSender;
  private final SecureRandom secureRandom = new SecureRandom();

  public record OtpRequestResult(UUID challengeUid, long expiresInSec, long resendAfterSec) {
  }

  public record AuthResult(String accessToken, String refreshToken, boolean newUser) {
  }

  @Transactional
  public OtpRequestResult requestOtp(String rawEmail, ClientKind clientKind, String ipAddress) {
    byte[] emailHash = emailCipher.hash(rawEmail);

    if (!rateLimiter.tryAcquireForRequest(emailHash, ipAddress)) {
      throw new TooManyRequestsException();
    }

    // Na e-mail smí být aktivní nejvýš jedna výzva, jinak by šlo nafarmit desítky pokusů
    // uhodnout 6místný kód (docs/soukromi.md).
    challengeRepository.invalidateActiveChallenges(emailHash, OffsetDateTime.now());

    String code = generateSixDigitCode();
    LoginChallenge challenge = LoginChallenge.builder()
        .emailHash(emailHash)
        .codeHash(codeEncoder.encode(code))
        .purpose(ChallengePurpose.LOGIN)
        .expiresAt(OffsetDateTime.now().plus(properties.getCodeTtl()))
        .maxAttempts((short) properties.getMaxAttempts())
        .ipHash(ipAddress == null ? null : emailCipher.hash(ipAddress))
        .clientKind(clientKind)
        .build();
    challenge = challengeRepository.save(challenge);

    if (properties.isMailEnabled()) {
      // Uložená preference existujícího účtu, jinak Accept-Language requestu (LocaleContextHolder
      // je v tuhle chvíli vyplněný z UserAwareLocaleResolveru) — nový uživatel žádnou preferenci
      // ještě nemá. Nerozlišuje se od "účet neexistuje" navenek (docs/soukromi.md), liší se jen
      // jazyk e-mailu, který vidí výhradně majitel schránky.
      Locale locale = appUserRepository.findByEmailHash(emailHash)
          .map(AppUser::getLocale)
          .filter(l -> l != null && !l.isBlank())
          .map(Locale::forLanguageTag)
          .orElseGet(LocaleContextHolder::getLocale);
      mailSender.sendOtpCode(rawEmail, code, locale);
    } else {
      log.info("[DEV] OTP kód pro e-mail {} (challenge {}): {}",
          emailCipher.normalize(rawEmail), challenge.getChallengeUid(), code);
    }

    return new OtpRequestResult(
        challenge.getChallengeUid(),
        properties.getCodeTtl().toSeconds(),
        properties.getResendCooldown().toSeconds());
  }

  /**
   * @param email Posílá se znovu i při ověření (ne jen při requestu) — challenge si ukládá jen
   *              email_hash, ne samotný e-mail, takže při JIT registraci nového účtu potřebujeme
   *              e-mail zvenčí, abychom mohli spočítat email_enc. Zároveň to přidává druhou
   *              vazbu navíc k challengeUid (viz docs/soukromi.md).
   */
  @Transactional
  public AuthResult verifyOtp(UUID challengeUid, String code, String email, ClientKind clientKind,
      String deviceLabel) {
    LoginChallenge challenge = challengeRepository.findByChallengeUidAndConsumedAtIsNull(challengeUid)
        .orElseThrow(InvalidChallengeException::new);

    byte[] emailHash = emailCipher.hash(email);
    if (!java.util.Arrays.equals(emailHash, challenge.getEmailHash())) {
      throw new InvalidChallengeException();
    }

    if (challenge.getExpiresAt().isBefore(OffsetDateTime.now())) {
      throw new InvalidChallengeException();
    }

    // Atomický inkrement — brání souběžným požadavkům vyzkoušet víc pokusů, než dovoluje limit.
    int updated = challengeRepository.incrementAttempts(challenge.getId());
    if (updated == 0) {
      challenge.setConsumedAt(OffsetDateTime.now());
      challengeRepository.save(challenge);
      throw new InvalidChallengeException();
    }

    if (!codeEncoder.matches(code, challenge.getCodeHash())) {
      throw new InvalidChallengeException();
    }

    challenge.setConsumedAt(OffsetDateTime.now());
    challengeRepository.save(challenge);

    boolean isNewUser = false;
    AppUser user = appUserRepository.findByEmailHash(emailHash).orElse(null);
    if (user == null) {
      isNewUser = true;
      HandleGenerator.GeneratedHandle handle = handleGenerator.generateUnique();
      user = AppUser.builder()
          .emailHash(emailHash)
          .emailEnc(emailCipher.encrypt(email))
          .emailDomain(emailCipher.extractDomain(email))
          .publicHandle(handle.canonicalHandle())
          .handleAdjective(handle.adjective())
          .handleNoun(handle.noun().key())
          .handleNumber((short) handle.number())
          .status(AppUserStatus.ACTIVE)
          .tokenVersion(0)
          .build();
    }
    user.setLastLoginAt(OffsetDateTime.now());
    user = appUserRepository.save(user);

    String accessToken = jwtService.issueAccessToken(user);
    RefreshTokenService.IssuedToken refreshToken =
        refreshTokenService.issueNewFamily(user, clientKind, deviceLabel);

    return new AuthResult(accessToken, refreshToken.rawToken(), isNewUser);
  }

  private String generateSixDigitCode() {
    // Bez vedoucí nuly kvůli čitelnosti, rozsah 100000–999999.
    int value = 100_000 + secureRandom.nextInt(900_000);
    return Integer.toString(value);
  }
}
