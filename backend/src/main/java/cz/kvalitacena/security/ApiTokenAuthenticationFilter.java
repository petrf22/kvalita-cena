package cz.kvalitacena.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cz.kvalitacena.db.entity.ApiTokenScope;
import cz.kvalitacena.db.repo.ApiTokenRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Strojový token workeru účtenek (auth.api_token, docs/ai.md, „Účtenky") — {@code Bearer kcw_…}.
 * Principal je {@link WorkerPrincipal}, ne UUID uživatele, takže {@link ViewerContextResolver}
 * z něj udělá anonyma: stroj nesmí zdědit nic z toho, co smí přihlášený člověk (zápis cen,
 * recenze). Jediné, na co dosáhne, je {@code ROLE_RECEIPT_WORKER}.
 *
 * <p>Revokovaný token přestane platit nejpozději za 60 s (cache), stejně jako pozastavený
 * účet u {@link JwtAuthenticationFilter}.
 */
@Component
@RequiredArgsConstructor
public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

  public static final String TOKEN_PREFIX = "kcw_";

  private final ApiTokenRepository apiTokenRepository;

  /** Identita stroje v {@code Authentication.getPrincipal()}. */
  public record WorkerPrincipal(Long tokenId, String name) {
  }

  private record CachedToken(Long id, String name, ApiTokenScope scope) {
  }

  private final Cache<String, Optional<CachedToken>> tokenCache = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofSeconds(60))
      .maximumSize(100)
      .build();

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer " + TOKEN_PREFIX)) {
      String token = header.substring("Bearer ".length());
      byte[] hash = sha256(token);
      Optional<CachedToken> cached = tokenCache.get(HexFormat.of().formatHex(hash),
          key -> apiTokenRepository.findByTokenHashAndRevokedAtIsNull(hash)
              .map(t -> {
                apiTokenRepository.touch(t.getId());
                return new CachedToken(t.getId(), t.getName(), t.getScope());
              }));
      cached.filter(t -> t.scope() == ApiTokenScope.RECEIPT_WORKER).ifPresent(t -> {
        var authentication = new UsernamePasswordAuthenticationToken(
            new WorkerPrincipal(t.id(), t.name()), null,
            List.of(new SimpleGrantedAuthority("ROLE_RECEIPT_WORKER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      });
    }
    filterChain.doFilter(request, response);
  }

  public static byte[] sha256(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 není dostupné", e);
    }
  }
}
