package cz.kvalitacena.security;

import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.service.TrustLevelService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Vyrábí {@link ViewerContext} z {@link Authentication} — každý GraphQL controller ho zavolá
 * jednou na začátku metody místo dřívějšího vlastního {@code publicUidOf(Authentication)}.
 */
@Component
@RequiredArgsConstructor
public class ViewerContextResolver {

  private final AppUserRepository appUserRepository;
  private final TrustLevelService trustLevelService;

  public ViewerContext resolve(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof UUID publicUid)) {
      return ViewerContext.ANONYMOUS;
    }
    return appUserRepository.findByPublicUid(publicUid)
        .map(user -> new ViewerContext(publicUid, user.getId(), trustLevelService.isTrusted(user), user.isModerator()))
        .orElse(ViewerContext.ANONYMOUS);
  }
}
