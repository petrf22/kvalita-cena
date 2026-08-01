package cz.kvalitacena.security;

import cz.kvalitacena.config.RefreshTokenProperties;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.ClientKind;
import cz.kvalitacena.db.entity.RefreshToken;
import cz.kvalitacena.db.entity.RevokeReason;
import cz.kvalitacena.db.repo.RefreshTokenRepository;
import cz.kvalitacena.exception.RefreshTokenInvalidException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * Rotace refresh tokenů s detekcí reuse — viz docs/soukromi.md a plán projektu.
 *
 * <p>Syrový token se NIKDY neukládá, jen jeho SHA-256 hash. Při každém obnovení (rotate) starý
 * token zanikne a vydá se nový ve stejné "rodině" ({@code familyUid}). Použití již jednou
 * použitého tokenu MIMO grace window znamená, že token někdo ukradl a použil dřív než legitimní
 * klient — proto se revokuje CELÁ rodina, ne jen ten jeden token.
 *
 * <p>Zjednodušení oproti plnému návrhu: uvnitř grace window (výpadek mobilní sítě, klient si
 * zopakuje request) nevracíme identický potomek (ten bychom museli držet v paměti navíc),
 * ale prostě povolíme další rotaci ze stejného rodiče. Bezpečnostní vlastnost je zachována
 * (žádné tiché opakované použití mimo okno), v nejhorším případě vznikne jeden řádek navíc.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

  private final RefreshTokenRepository refreshTokenRepository;
  private final RefreshTokenProperties properties;
  private final SecureRandom secureRandom = new SecureRandom();

  public record IssuedToken(String rawToken, RefreshToken entity) {
  }

  @Transactional
  public IssuedToken issueNewFamily(AppUser user, ClientKind clientKind, String deviceLabel) {
    return issue(user, clientKind, deviceLabel, UUID.randomUUID(), null);
  }

  @Transactional
  public IssuedToken rotate(String rawToken, ClientKind clientKind, String deviceLabel) {
    byte[] hash = sha256(rawToken);
    RefreshToken current = refreshTokenRepository.findByTokenHash(hash)
        .orElseThrow(RefreshTokenInvalidException::new);

    OffsetDateTime now = OffsetDateTime.now();

    if (current.getRevokedAt() != null) {
      throw new RefreshTokenInvalidException();
    }
    if (current.getExpiresAt().isBefore(now)) {
      throw new RefreshTokenInvalidException();
    }
    if (current.getUsedAt() != null) {
      // Grace window se počítá VŽDY od prvního použití, nikdy se neposouvá — jinak by šlo
      // okno prodlužovat donekonečna opakovaným replayem ukradeného tokenu v intervalech
      // kratších než reuseGracePeriod a detekce by se nikdy nespustila.
      boolean withinGrace = current.getUsedAt().plus(properties.getReuseGracePeriod()).isAfter(now);
      if (!withinGrace) {
        int revoked = refreshTokenRepository.revokeAllByFamilyUid(
            current.getFamilyUid(), RevokeReason.REUSE_DETECTED, now);
        log.warn("Detekováno znovupoužití refresh tokenu mimo grace window, revokována "
            + "celá rodina {} ({} tokenů) — pravděpodobná krádež tokenu.",
            current.getFamilyUid(), revoked);
        throw new RefreshTokenInvalidException();
      }
      // V grace window: tolerujeme opakování (ztracená odpověď), ale usedAt NEMĚNÍME.
    } else {
      current.setUsedAt(now);
      refreshTokenRepository.save(current);
    }

    return issue(current.getUser(), clientKind, deviceLabel, current.getFamilyUid(), current);
  }

  private IssuedToken issue(AppUser user, ClientKind clientKind, String deviceLabel,
      UUID familyUid, RefreshToken parent) {
    // user může být lazy proxy (current.getUser() z rotate()) — inicializovat, dokud je session
    // ještě otevřená. Volající (AuthController) z entity vyčítá uživatele až PO skončení
    // transakce, kdy by se lazy proxy jinak neinicializovala (LazyInitializationException).
    org.hibernate.Hibernate.initialize(user);
    String rawToken = generateRawToken();
    Duration ttl = clientKind == ClientKind.ANDROID ? properties.getAndroidTtl() : properties.getWebTtl();

    RefreshToken token = RefreshToken.builder()
        .familyUid(familyUid)
        .tokenHash(sha256(rawToken))
        .user(user)
        .parent(parent)
        .clientKind(clientKind)
        .deviceLabel(deviceLabel)
        .expiresAt(OffsetDateTime.now().plus(ttl))
        .build();

    return new IssuedToken(rawToken, refreshTokenRepository.save(token));
  }

  @Transactional
  public void revokeAllForUser(Long userId, RevokeReason reason) {
    refreshTokenRepository.revokeAllByUserId(userId, reason, OffsetDateTime.now());
  }

  /** Odhlášení jednoho zařízení — revokuje jen rodinu tokenu, který se odhlašuje. */
  @Transactional
  public void revokeFamilyByRawToken(String rawToken) {
    refreshTokenRepository.findByTokenHash(sha256(rawToken)).ifPresent(token ->
        refreshTokenRepository.revokeAllByFamilyUid(token.getFamilyUid(), RevokeReason.LOGOUT, OffsetDateTime.now()));
  }

  /** Denní úklid dávno revokovaných/expirovaných záznamů (mirror JwtService.cleanup v prani-be). */
  @Scheduled(cron = "@daily")
  @Transactional
  public void cleanup() {
    int deleted = refreshTokenRepository.deleteAllRevokedExpiredBefore(
        OffsetDateTime.now().minusDays(30));
    if (deleted > 0) {
      log.info("Úklid refresh tokenů: smazáno {} dávno revokovaných/expirovaných záznamů.", deleted);
    }
  }

  private String generateRawToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
