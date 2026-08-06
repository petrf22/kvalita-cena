package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.service.TrustLevelService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class ViewerGraphQlController {

  private final AppUserRepository appUserRepository;
  private final TrustLevelService trustLevelService;

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

  private Viewer toViewer(AppUser user) {
    return new Viewer(user.getPublicHandle(), user.getDisplayName(), user.getCreatedAt(),
        trustLevelService.isTrusted(user));
  }
}
