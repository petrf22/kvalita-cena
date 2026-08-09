package cz.kvalitacena.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.repo.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Nejdřív uložená preference přihlášeného uživatele ({@code auth.app_user.locale}), pak
 * {@code Accept-Language}. {@link JwtAuthenticationFilter} běží PŘED touto vrstvou ve filter
 * chainu (Spring Security filtry mají přednost před {@code DispatcherServlet}), takže
 * {@link SecurityContextHolder} je v okamžiku volání {@link #resolveLocale} už vyplněný.
 *
 * <p>Cache místo dotazu na každý request — {@code setLocale} v {@code ViewerGraphQlController}
 * ji po uložení nové preference invaliduje, jinak by uživatel viděl starý jazyk e-mailu
 * až 5 minut po přepnutí (to je jen pro OTP e-mail, ne pro odpovědi API — ty čtou aktuální
 * {@code Accept-Language} vždy).
 */
public class UserAwareLocaleResolver extends AcceptHeaderLocaleResolver {

  private final AppUserRepository appUserRepository;
  private final Cache<UUID, Optional<Locale>> localeCache = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofMinutes(5))
      .build();

  public UserAwareLocaleResolver(AppUserRepository appUserRepository, I18nProperties properties) {
    this.appUserRepository = appUserRepository;
    setSupportedLocales(properties.getSupportedLocales().stream().map(Locale::forLanguageTag).toList());
    setDefaultLocale(Locale.forLanguageTag(properties.getDefaultLocale()));
  }

  @Override
  public Locale resolveLocale(HttpServletRequest request) {
    return userLocale().orElseGet(() -> super.resolveLocale(request));
  }

  private Optional<Locale> userLocale() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof UUID publicUid)) {
      return Optional.empty();
    }
    return localeCache.get(publicUid, uid -> appUserRepository.findByPublicUid(uid)
        .map(AppUser::getLocale)
        .filter(locale -> locale != null && !locale.isBlank())
        .map(Locale::forLanguageTag));
  }

  /** Volá {@code ViewerGraphQlController.setLocale} hned po uložení, ať se nečeká na TTL. */
  public void invalidate(UUID publicUid) {
    localeCache.invalidate(publicUid);
  }
}
