package cz.kvalitacena.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cz.kvalitacena.config.FeedbackProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Proof-of-work výzva pro formulář zpětné vazby (docs/nasazeni.md, obrana proti spamu) —
 * náhrada CAPTCHY, kterou appka nesmí použít (docs/soukromi.md, "žádná analytika třetích
 * stran, žádné externí fonty ani CDN"). Klient musí najít {@code nonce} takové, že
 * {@code SHA-256(salt + ":" + nonce)} má {@code difficulty} vedoucích nulových bitů — levné
 * ověřit, drahé vyřešit, žádná třetí strana nikde v procesu.
 *
 * <p>Token nese celou výzvu (salt, kdy vyprší, jakou má obtížnost) podepsanou HMAC-SHA256, takže
 * appka nemusí nic ukládat, dokud výzvu někdo skutečně nevyřeší — teprve {@link #verify} zapíše
 * {@code salt} do krátkodobé cache, aby jedno řešení nešlo přehrát tisíckrát ({@code replay}).
 * Klíč je odvozený z {@code app.jwt.secret} pevným info-stringem (HMAC jako KDF) — žádná nová
 * proměnná prostředí navíc jen pro tohle.
 */
@Service
@RequiredArgsConstructor
public class FeedbackChallengeService {

  private static final String HMAC_ALGO = "HmacSHA256";
  private static final String KEY_DERIVATION_INFO = "feedback-challenge";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();

  @Value("${app.jwt.secret}")
  private String jwtSecretBase64;

  private final FeedbackProperties feedbackProperties;

  private SecretKeySpec derivedKey;
  // Salt každé úspěšně ověřené výzvy — brání přehrání téhož řešení podruhé. TTL = platnost
  // výzvy samotné, déle salt appka nepotřebuje pamatovat (starší token je stejně INVALID).
  private Cache<String, Boolean> usedSalts;

  @PostConstruct
  void init() {
    byte[] jwtSecret = Base64.getDecoder().decode(jwtSecretBase64);
    derivedKey = new SecretKeySpec(hmac(jwtSecret, KEY_DERIVATION_INFO.getBytes(StandardCharsets.UTF_8)), HMAC_ALGO);
    usedSalts = Caffeine.newBuilder()
        .expireAfterWrite(feedbackProperties.getChallenge().getTtl())
        .build();
  }

  public record IssuedChallenge(String token, String salt, int difficulty) {}

  public enum Outcome {
    /** Kryptograficky platné řešení, vyřešené v rozumném čase. */
    VALID,
    /** Kryptograficky platné, ale odpověď přišla podezřele rychle po vydání výzvy. */
    TOO_FAST,
    /** Podpis nesedí, výzva vypršela, hash nesplňuje obtížnost, nebo byl salt už použitý. */
    INVALID
  }

  public IssuedChallenge issue() {
    byte[] saltBytes = new byte[16];
    RANDOM.nextBytes(saltBytes);
    String salt = BASE64.encodeToString(saltBytes);
    int difficulty = feedbackProperties.getChallenge().getDifficulty();
    long expiresAtEpochMilli = Instant.now().plus(feedbackProperties.getChallenge().getTtl()).toEpochMilli();

    String payload = salt + ":" + expiresAtEpochMilli + ":" + difficulty;
    String token = BASE64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + sign(payload);
    return new IssuedChallenge(token, salt, difficulty);
  }

  public Outcome verify(String token, String nonce) {
    if (token == null || nonce == null || nonce.isBlank()) return Outcome.INVALID;

    int dot = token.indexOf('.');
    if (dot < 0) return Outcome.INVALID;

    String payload;
    try {
      payload = new String(BASE64_DECODER.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return Outcome.INVALID;
    }

    if (!constantTimeEquals(sign(payload), token.substring(dot + 1))) {
      return Outcome.INVALID; // podvržený nebo poškozený token
    }

    String[] parts = payload.split(":", 3);
    if (parts.length != 3) return Outcome.INVALID;

    long expiresAtEpochMilli;
    int difficulty;
    try {
      expiresAtEpochMilli = Long.parseLong(parts[1]);
      difficulty = Integer.parseInt(parts[2]);
    } catch (NumberFormatException e) {
      return Outcome.INVALID;
    }
    String salt = parts[0];

    Instant now = Instant.now();
    Instant expiresAt = Instant.ofEpochMilli(expiresAtEpochMilli);
    if (now.isAfter(expiresAt)) return Outcome.INVALID;

    if (Boolean.TRUE.equals(usedSalts.getIfPresent(salt))) return Outcome.INVALID; // replay

    if (!hasLeadingZeroBits(sha256(salt + ":" + nonce), difficulty)) return Outcome.INVALID;

    usedSalts.put(salt, Boolean.TRUE);

    Instant issuedAt = expiresAt.minus(feedbackProperties.getChallenge().getTtl());
    boolean tooFast = now.isBefore(issuedAt.plus(feedbackProperties.getChallenge().getMinAge()));
    return tooFast ? Outcome.TOO_FAST : Outcome.VALID;
  }

  private String sign(String payload) {
    return BASE64.encodeToString(hmac(derivedKey.getEncoded(), payload.getBytes(StandardCharsets.UTF_8)));
  }

  private byte[] hmac(byte[] key, byte[] message) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGO);
      mac.init(new SecretKeySpec(key, HMAC_ALGO));
      return mac.doFinal(message);
    } catch (Exception e) {
      throw new IllegalStateException("HMAC-SHA256 selhalo", e);
    }
  }

  private byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private boolean hasLeadingZeroBits(byte[] hash, int bits) {
    int fullBytes = bits / 8;
    for (int i = 0; i < fullBytes && i < hash.length; i++) {
      if (hash[i] != 0) return false;
    }
    int remainingBits = bits % 8;
    if (remainingBits == 0 || fullBytes >= hash.length) return true;
    int mask = (0xFF << (8 - remainingBits)) & 0xFF;
    return (hash[fullBytes] & mask) == 0;
  }

  private boolean constantTimeEquals(String expected, String actual) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }
}
