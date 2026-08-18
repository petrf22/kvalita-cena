package cz.kvalitacena.controller;

import cz.kvalitacena.config.I18nProperties;
import cz.kvalitacena.config.UserAwareLocaleResolver;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.service.CountryResolver;
import cz.kvalitacena.service.HandleRenderer;
import cz.kvalitacena.service.MediaService;
import cz.kvalitacena.service.TrustLevelService;
import cz.kvalitacena.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class ViewerGraphQlController {

  private final AppUserRepository appUserRepository;
  private final TrustLevelService trustLevelService;
  private final HandleRenderer handleRenderer;
  private final I18nProperties i18nProperties;
  private final UserAwareLocaleResolver userAwareLocaleResolver;
  private final UserProfileService userProfileService;
  private final MediaService mediaService;
  private final CountryResolver countryResolver;

  @QueryMapping
  public List<CountryInfo> countries() {
    return countryResolver.supportedCountries();
  }

  /** Nikdy e-mail ani DB id (docs/soukromi.md) — jen veřejná identita přihlášeného uživatele. */
  @QueryMapping
  public Viewer me(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof UUID publicUid)) {
      return null;
    }
    return appUserRepository.findByPublicUid(publicUid)
        .map(this::toViewer)
        .orElse(null);
  }

  /**
   * Volba jazyka je autoritativně na klientovi a TLAČÍ se sem — {@code app_user.locale} slouží
   * jen asynchronnímu výstupu (OTP e-mail, později notifikace), synchronní odpovědi API se pořád
   * řídí Accept-Language (docs/lokalizace.md). {@code country} je volitelný (řídí měnu/ARES atd.,
   * viz CurrencyResolver) — když chybí, odvodí se z jazyka přes {@code app.i18n.country-locale}.
   */
  @MutationMapping
  public Viewer setLocale(@Argument String locale, @Argument String country, Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof UUID publicUid)) {
      throw new UnauthorizedException(ErrorCode.LOCALE_REQUIRES_LOGIN);
    }
    if (!i18nProperties.getSupportedLocales().contains(locale)) {
      throw new ValidationException(ErrorCode.LOCALE_UNSUPPORTED, locale);
    }
    AppUser user = appUserRepository.findByPublicUid(publicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.ACCOUNT_GONE));
    user.setLocale(locale);
    user.setCountry(country != null ? country : countryFor(locale));
    user = appUserRepository.save(user);
    // Bez tohohle by uživatel viděl starý jazyk e-mailu až 5 minut po přepnutí (TTL cache
    // v UserAwareLocaleResolver) — synchronní odpovědi to nepostihne, ty čtou Accept-Language.
    userAwareLocaleResolver.invalidate(publicUid);
    return toViewer(user);
  }

  /**
   * Avatar se nahrává přes REST (MediaService.uploadAvatar, multipart), tahle mutace řeší
   * zbytek profilu — jméno, příjmení, přezdívka, telefon, kontaktní e-mail, viditelnost
   * (docs/soukromi.md, "Profil uživatele a viditelnost"). Vyžaduje přihlášení.
   */
  @MutationMapping
  public Viewer updateProfile(@Argument UpdateProfileInput input, Authentication authentication) {
    AppUser user = requireCurrentUser(authentication);
    userProfileService.update(user, input);
    return toViewer(user);
  }

  /** Smazání avatara profilu. Vyžaduje přihlášení. */
  @MutationMapping
  public Viewer deleteAvatar(Authentication authentication) {
    AppUser user = requireCurrentUser(authentication);
    mediaService.deleteAvatar(user.getPublicUid());
    return toViewer(user);
  }

  private AppUser requireCurrentUser(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof UUID publicUid)) {
      throw new UnauthorizedException(ErrorCode.PROFILE_REQUIRES_LOGIN);
    }
    return appUserRepository.findByPublicUid(publicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.ACCOUNT_GONE));
  }

  private String countryFor(String locale) {
    return i18nProperties.getCountryLocale().entrySet().stream()
        .filter(e -> e.getValue().equals(locale))
        .map(java.util.Map.Entry::getKey)
        .findFirst()
        .orElse(i18nProperties.getDefaultCountry());
  }

  private Viewer toViewer(AppUser user) {
    return new Viewer(handleRenderer.render(user), user.getDisplayName(), user.getCreatedAt(),
        trustLevelService.isTrusted(user), user.isModerator(), user.getLocale(), user.getCountry(),
        userProfileService.load(user));
  }
}
