package cz.kvalitacena.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.AppUserStatus;
import cz.kvalitacena.db.repo.AppUserRepository;
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
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Bez Bearer hlavičky request pokračuje jako anonymní (žádná chyba) — anonymní přístup je
 * legitimní úroveň T0 (docs/reputace.md), ne selhání. Autorizace jednotlivých polí/dotazů se
 * řeší až v GraphQL vrstvě podle ViewerContext, ne tady.
 *
 * <p>Krátká životnost access tokenu znamená, že není potřeba revokační seznam — okamžité
 * globální odhlášení se řeší inkrementem {@code token_version}, který se sem promítne do
 * {@link #tokenVersionCache} nejpozději za 60 s.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final JwtService jwtService;
  private final AppUserRepository appUserRepository;

  // Drží i status a roli, ne jen token_version — pozastavený účet (docs/podminky-uziti.md,
  // "Ukončení a vyloučení") se tak přestane autentizovat nejpozději do 60 s bez nutnosti
  // revokačního seznamu, stejným mechanismem jako globální odhlášení přes token_version.
  private record CachedUser(int tokenVersion, AppUserStatus status, boolean moderator) {
  }

  private final Cache<UUID, CachedUser> userCache = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofSeconds(60))
      .build();

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String header = request.getHeader("Authorization");

    if (header != null && header.startsWith("Bearer ")) {
      String token = header.substring("Bearer ".length());
      jwtService.parse(token).ifPresent(parsed -> {
        CachedUser cached = userCache.get(parsed.publicUid(),
            uid -> appUserRepository.findByPublicUid(uid)
                .map(u -> new CachedUser(u.getTokenVersion(), u.getStatus(), u.isModerator()))
                .orElse(null));

        if (cached != null && cached.tokenVersion() == parsed.tokenVersion()
            && cached.status() == AppUserStatus.ACTIVE) {
          List<SimpleGrantedAuthority> authorities = cached.moderator()
              ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_MODERATOR"))
              : List.of(new SimpleGrantedAuthority("ROLE_USER"));
          var authentication = new UsernamePasswordAuthenticationToken(
              parsed.publicUid(), null, authorities);
          SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        // Jinak (token_version se změnil = globální odhlášení, účet zmizel, nebo status není
        // ACTIVE = pozastavený/anonymizovaný) request zůstává neautentizovaný — chráněné
        // endpointy pak samy vrátí 401.
      });
    }

    filterChain.doFilter(request, response);
  }
}
