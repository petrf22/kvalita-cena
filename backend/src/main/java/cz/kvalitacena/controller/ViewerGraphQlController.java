package cz.kvalitacena.controller;

import cz.kvalitacena.config.I18nProperties;
import cz.kvalitacena.config.UserAwareLocaleResolver;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.HandleGenerator;
import cz.kvalitacena.service.Messages;
import cz.kvalitacena.service.TrustLevelService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class ViewerGraphQlController {

  private final AppUserRepository appUserRepository;
  private final TrustLevelService trustLevelService;
  private final Messages messages;
  private final I18nProperties i18nProperties;
  private final UserAwareLocaleResolver userAwareLocaleResolver;

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

  private String countryFor(String locale) {
    return i18nProperties.getCountryLocale().entrySet().stream()
        .filter(e -> e.getValue().equals(locale))
        .map(java.util.Map.Entry::getKey)
        .findFirst()
        .orElse(i18nProperties.getDefaultCountry());
  }

  private Viewer toViewer(AppUser user) {
    return new Viewer(renderedHandle(user), user.getDisplayName(), user.getCreatedAt(),
        trustLevelService.isTrusted(user), user.getLocale(), user.getCountry());
  }

  /**
   * "Modrý čáp #4271" se skládá až tady, podle jazyka requestu — {@code public_handle} v DB je
   * jazykově neutrální klíč (docs/lokalizace.md, {@link HandleGenerator}). Pád na kanonický
   * tvar, kdyby handle_adjective/handle_noun z nějakého důvodu chyběly (stará/nekonzistentní data).
   */
  private String renderedHandle(AppUser user) {
    if (user.getHandleAdjective() == null || user.getHandleNoun() == null || user.getHandleNumber() == null) {
      return user.getPublicHandle();
    }
    HandleGenerator.Gender gender = HandleGenerator.NOUN_GENDERS.getOrDefault(
        user.getHandleNoun(), HandleGenerator.Gender.M);
    String adjective = messages.get("handle.adjective." + user.getHandleAdjective() + "." + gender);
    String noun = messages.get("handle.noun." + user.getHandleNoun());
    // String, ne Short/Integer — MessageFormat by na Number automaticky použil NumberFormat
    // dané lokality a vložil tisícový oddělovač ("2 428" místo "2428").
    return messages.get("handle.format", adjective, noun, String.valueOf(user.getHandleNumber()));
  }
}
