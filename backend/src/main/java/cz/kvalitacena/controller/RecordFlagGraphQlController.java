package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.RecordType;
import cz.kvalitacena.security.ViewerContextResolver;
import cz.kvalitacena.service.RecordFlagService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

/** Nahlášení zboží/obchodu (core.record_flag) — společné pro oba typy záznamů. */
@Controller
@RequiredArgsConstructor
public class RecordFlagGraphQlController {

  private final RecordFlagService recordFlagService;
  private final ViewerContextResolver viewerContextResolver;

  @MutationMapping
  public FlagResult flagRecord(@Argument RecordType recordType, @Argument Long recordId,
      @Argument String reason, Authentication authentication) {
    return recordFlagService.flag(recordType, recordId, reason,
        viewerContextResolver.resolve(authentication).publicUid());
  }
}
