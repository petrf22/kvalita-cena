package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.ClientKind;
import cz.kvalitacena.security.ClientIpResolver;
import cz.kvalitacena.security.FeedbackChallengeService;
import cz.kvalitacena.security.ViewerContextResolver;
import cz.kvalitacena.service.CountryResolver;
import cz.kvalitacena.service.FeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Zpětná vazba od uživatele appky (core.feedback) — na rozdíl od {@link RecordFlagGraphQlController}
 * funguje i bez přihlášení, viz {@link FeedbackService}. Klient/verze/IP se čtou VŽDY ze serveru
 * (hlavičky, ne argument mutace), stejný vzor jako {@code ObservationGraphQlController.resolveSource}
 * a {@code AuthController.resolveClientKind} — appka nevěří klientem tvrzené hodnotě. IP jde přes
 * sdílený {@link ClientIpResolver}, ne vlastní parsování {@code X-Forwarded-For}.
 */
@Controller
@RequiredArgsConstructor
public class FeedbackGraphQlController {

  private final FeedbackService feedbackService;
  private final ViewerContextResolver viewerContextResolver;
  private final CountryResolver countryResolver;
  private final LocaleResolver localeResolver;
  private final ClientIpResolver clientIpResolver;
  private final FeedbackChallengeService feedbackChallengeService;
  private final HttpServletRequest servletRequest;

  @QueryMapping
  public FeedbackChallenge feedbackChallenge() {
    var issued = feedbackChallengeService.issue();
    return new FeedbackChallenge(issued.token(), issued.salt(), issued.difficulty());
  }

  @MutationMapping
  public FeedbackResult submitFeedback(@Argument FeedbackInput input, Authentication authentication) {
    var viewer = viewerContextResolver.resolve(authentication);
    ClientKind clientKind = resolveClientKind();
    String locale = localeResolver.resolveLocale(servletRequest).toLanguageTag();
    String country = countryResolver.resolve(null, viewer.userId());
    return feedbackService.submit(input, viewer.userId(), clientKind, clientIpResolver.resolve(servletRequest),
        resolvePlatformInfo(), locale, country);
  }

  private ClientKind resolveClientKind() {
    String header = servletRequest.getHeader("X-Client-Kind");
    return "ANDROID".equalsIgnoreCase(header) ? ClientKind.ANDROID : ClientKind.WEB;
  }

  private String resolvePlatformInfo() {
    String userAgent = servletRequest.getHeader("User-Agent");
    return userAgent == null ? null : userAgent.length() > 200 ? userAgent.substring(0, 200) : userAgent;
  }
}
