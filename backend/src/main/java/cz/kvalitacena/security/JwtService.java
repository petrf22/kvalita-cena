package cz.kvalitacena.security;

import cz.kvalitacena.db.entity.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Access token — krátká životnost (jednotky minut), proto žádný revokační seznam; okamžité
 * globální odhlášení řeší inkrement {@code token_version} (viz JwtAuthenticationFilter).
 * {@code sub} je {@code public_uid}, nikdy databázové id (docs/soukromi.md).
 */
@Service
public class JwtService {

  @Value("${app.jwt.secret}")
  private String secretBase64;

  @Value("${app.jwt.access-token-ttl}")
  private Duration accessTokenTtl;

  private SecretKey secretKey;

  @PostConstruct
  void init() {
    secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretBase64));
  }

  public String issueAccessToken(AppUser user) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(user.getPublicUid().toString())
        .claim("tv", user.getTokenVersion())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(accessTokenTtl)))
        .signWith(secretKey)
        .compact();
  }

  public Optional<ParsedAccessToken> parse(String token) {
    try {
      Claims claims = Jwts.parser().verifyWith(secretKey).build()
          .parseSignedClaims(token)
          .getPayload();
      UUID publicUid = UUID.fromString(claims.getSubject());
      int tokenVersion = claims.get("tv", Integer.class);
      return Optional.of(new ParsedAccessToken(publicUid, tokenVersion));
    } catch (JwtException | IllegalArgumentException e) {
      // Neplatný/prošlý/podvržený token — request pokračuje jako anonymní, viz JwtAuthenticationFilter.
      return Optional.empty();
    }
  }

  public record ParsedAccessToken(UUID publicUid, int tokenVersion) {
  }
}
