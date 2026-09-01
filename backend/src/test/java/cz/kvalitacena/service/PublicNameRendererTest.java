package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.ProfileField;
import cz.kvalitacena.security.ViewerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Recenze je první veřejné místo, kde autor vyleze z API (docs/soukromi.md) — proto tři cesty
 * musí platit přesně: veřejná přezdívka se ukáže s číslem, neveřejná/chybějící přezdívka
 * spadne na handle, a klient nikdy nesestavuje jméno sám (vždy hotový řetězec ze serveru).
 */
@ExtendWith(MockitoExtension.class)
class PublicNameRendererTest {

  private static final Long AUTHOR_ID = 7L;
  private static final ViewerContext SOME_VIEWER = new ViewerContext(UUID.randomUUID(), 99L, false, false);

  @Mock
  private HandleRenderer handleRenderer;
  @Mock
  private UserProfileService userProfileService;
  @Mock
  private Messages messages;

  private PublicNameRenderer renderer() {
    return new PublicNameRenderer(handleRenderer, userProfileService, messages);
  }

  @Test
  void publicDisplayNameIsUsedWithHandleNumberSuffix() {
    AppUser author = AppUser.builder().id(AUTHOR_ID).displayName("Petr").handleNumber((short) 4271).build();
    when(userProfileService.isFieldVisible(AUTHOR_ID, ProfileField.DISPLAY_NAME, SOME_VIEWER)).thenReturn(true);
    when(messages.get(eq("handle.customFormat"), eq("Petr"), eq("4271"))).thenReturn("Petr #4271");

    String result = renderer().render(author, SOME_VIEWER);

    assertThat(result).isEqualTo("Petr #4271");
    verifyNoInteractions(handleRenderer);
  }

  @Test
  void privateDisplayNameFallsBackToHandle() {
    AppUser author = AppUser.builder().id(AUTHOR_ID).displayName("Petr").handleNumber((short) 4271).build();
    when(userProfileService.isFieldVisible(AUTHOR_ID, ProfileField.DISPLAY_NAME, SOME_VIEWER)).thenReturn(false);
    when(handleRenderer.render(author)).thenReturn("Modrý čáp #4271");

    String result = renderer().render(author, SOME_VIEWER);

    assertThat(result).isEqualTo("Modrý čáp #4271");
  }

  @Test
  void missingDisplayNameFallsBackToHandleWithoutCheckingVisibility() {
    AppUser author = AppUser.builder().id(AUTHOR_ID).displayName(null).handleNumber((short) 4271).build();
    when(handleRenderer.render(author)).thenReturn("Modrý čáp #4271");

    String result = renderer().render(author, SOME_VIEWER);

    assertThat(result).isEqualTo("Modrý čáp #4271");
  }

  @Test
  void missingHandleNumberFallsBackToHandleEvenWithPublicDisplayName() {
    AppUser author = AppUser.builder().id(AUTHOR_ID).displayName("Petr").handleNumber(null).build();
    when(handleRenderer.render(author)).thenReturn("Modrý čáp #4271");

    String result = renderer().render(author, SOME_VIEWER);

    assertThat(result).isEqualTo("Modrý čáp #4271");
  }
}
