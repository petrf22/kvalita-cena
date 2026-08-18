package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.security.HandleGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * "Modrý čáp #4271" se skládá až podle jazyka requestu — {@code public_handle} v DB je jazykově
 * neutrální klíč (docs/lokalizace.md, {@link HandleGenerator}). Vytažené z {@code
 * ViewerGraphQlController} do vlastní service, protože handle potřebuje vykreslit i moderace
 * ({@code ModerationService}), ne jen vlastní profil.
 */
@Service
@RequiredArgsConstructor
public class HandleRenderer {

  private final Messages messages;

  /** Pád na kanonický tvar, kdyby handle_adjective/handle_noun z nějakého důvodu chyběly. */
  public String render(AppUser user) {
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
