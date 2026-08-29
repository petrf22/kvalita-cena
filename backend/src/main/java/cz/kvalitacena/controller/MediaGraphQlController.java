package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Media;
import cz.kvalitacena.db.entity.PhotoKind;
import cz.kvalitacena.security.ViewerContext;
import cz.kvalitacena.security.ViewerContextResolver;
import cz.kvalitacena.service.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

/** Úprava a smazání fotky (upload samotný jde přes REST {@code MediaController}). */
@Controller
@RequiredArgsConstructor
public class MediaGraphQlController {

  private final MediaService mediaService;
  private final ViewerContextResolver viewerContextResolver;

  @MutationMapping
  public Photo updatePhoto(@Argument Long id, @Argument String caption, @Argument Integer sortOrder,
      @Argument PhotoKind kind, Authentication authentication) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    Media media = mediaService.update(id, caption, sortOrder, kind, viewer.publicUid());
    return mediaService.toPhoto(media, viewer);
  }

  @MutationMapping
  public boolean deletePhoto(@Argument Long id, Authentication authentication) {
    mediaService.delete(id, viewerContextResolver.resolve(authentication).publicUid());
    return true;
  }
}
