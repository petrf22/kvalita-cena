package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.ProfileField;
import cz.kvalitacena.security.ViewerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Veřejné jméno autora recenze — první místo v appce, kde autor vyleze z API na VEŘEJNÉM typu
 * (docs/soukromi.md, "Podepsaná recenze": dřív se autor objevoval jen v moderátorském pohledu,
 * gatovaný rolí). Vždy jedna ze dvou forem, nikdy reálné jméno:
 *
 * <ul>
 *   <li>má-li autor vyplněnou {@code display_name} A je viditelná podle profilové matice
 *       ({@link UserProfileService#isFieldVisible}), vrátí "{přezdívka} #{handle_number}" —
 *       číslo dolepené kvůli unikátnosti, protože přezdívky samy o sobě unikátní nejsou (dva
 *       lidé si zvolí "Petr")</li>
 *   <li>jinak vykreslený lokalizovaný handle ({@link HandleRenderer}), který číslo už nese</li>
 * </ul>
 *
 * <p>Vykreslení je vždy server-side podle jazyka ČTENÁŘE (Accept-Language requestu), nikdy
 * autora — klient handle skládat nesmí (docs/lokalizace.md, "Handle: strukturovaně kvůli
 * gramatickému rodu").
 */
@Service
@RequiredArgsConstructor
public class PublicNameRenderer {

  private final HandleRenderer handleRenderer;
  private final UserProfileService userProfileService;
  private final Messages messages;

  public String render(AppUser author, ViewerContext viewer) {
    String displayName = author.getDisplayName();
    if (displayName != null && !displayName.isBlank() && author.getHandleNumber() != null
        && userProfileService.isFieldVisible(author.getId(), ProfileField.DISPLAY_NAME, viewer)) {
      return messages.get("handle.customFormat", displayName, String.valueOf(author.getHandleNumber()));
    }
    return handleRenderer.render(author);
  }
}
