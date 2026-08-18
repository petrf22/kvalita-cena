package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.FlagResolution;
import cz.kvalitacena.db.entity.PriceObservation;
import cz.kvalitacena.db.entity.RecordType;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.security.ViewerContext;
import cz.kvalitacena.security.ViewerContextResolver;
import cz.kvalitacena.service.ModerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.UUID;

/**
 * Nástroj pro T4 (docs/reputace.md, "Odstupňování přístupu") — přezkum fronty nahlášených
 * záznamů, zamítání cen a pozastavování účtů. Autorizace je predikát tady v kontroleru
 * ({@code requireModerator}), stejná konvence jako {@code requireLoggedIn} v
 * {@link MyContributionsGraphQlController} — ne {@code @PreAuthorize}, ačkoli by fungovalo
 * ({@code SecurityConfig} má {@code @EnableMethodSecurity} zapnuté).
 */
@Controller
@RequiredArgsConstructor
public class ModerationGraphQlController {

  private final ModerationService moderationService;
  private final ViewerContextResolver viewerContextResolver;

  @QueryMapping
  public FlaggedRecordResult flaggedRecords(@Argument RecordType recordType, @Argument Integer first,
      @Argument Integer offset, Authentication authentication) {
    return moderationService.flaggedRecords(recordType, first, offset, requireModerator(authentication));
  }

  @QueryMapping
  public ModerationObservationResult moderationObservations(@Argument String authorPublicUid,
      @Argument Long productId, @Argument Long storeId, @Argument Integer first, @Argument Integer offset,
      Authentication authentication) {
    requireModerator(authentication);
    return moderationService.moderationObservations(parsePublicUid(authorPublicUid), productId, storeId, first, offset);
  }

  @MutationMapping
  public Boolean resolveFlags(@Argument RecordType recordType, @Argument Long recordId,
      @Argument FlagResolution resolution, Authentication authentication) {
    moderationService.resolveFlags(recordType, recordId, resolution, requireModerator(authentication));
    return true;
  }

  @MutationMapping
  public PriceObservation setObservationRejected(@Argument Long id, @Argument boolean rejected,
      @Argument String reason, Authentication authentication) {
    requireModerator(authentication);
    return moderationService.setObservationRejected(id, rejected, reason);
  }

  @MutationMapping
  public Boolean setUserSuspended(@Argument String publicUid, @Argument boolean suspended,
      @Argument String reason, Authentication authentication) {
    requireModerator(authentication);
    UUID parsed = parsePublicUid(publicUid);
    if (parsed == null) {
      throw new NotFoundException(ErrorCode.MODERATION_USER_NOT_FOUND);
    }
    moderationService.setUserSuspended(parsed, suspended, reason);
    return true;
  }

  private Long requireModerator(Authentication authentication) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    if (!viewer.moderator()) {
      throw new UnauthorizedException(ErrorCode.MODERATION_REQUIRES_ROLE);
    }
    return viewer.userId();
  }

  /** {@code publicUid} je volitelný filtr u moderationObservations — chybný formát se chová jako "žádný filtr". */
  private UUID parsePublicUid(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
