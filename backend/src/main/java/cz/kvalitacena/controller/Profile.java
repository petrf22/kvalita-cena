package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.ProfileVisibility;

import java.util.List;

/**
 * GraphQL typ {@code Profile} (viz {@code Viewer.profile}) — vždy PLNÝ pohled vlastníka na
 * vlastní profil (docs/soukromi.md, "Profil uživatele a viditelnost"), nikdy filtrovaný podle
 * {@code visibility}/{@code visibleFields}. Ty samé hodnoty řídí, co uvidí NĚKDO JINÝ — v etapě 1
 * to zatím není potřeba (žádný dotaz na cizí profil neexistuje), ale matice se ukládá už teď.
 *
 * <p>{@code firstName}/{@code lastName}/{@code phone}/{@code contactEmail} jsou dešifrované z
 * {@code auth.user_profile} ({@link cz.kvalitacena.security.EmailCipher}); {@code loginEmail} je
 * dešifrovaný {@code app_user.email_enc} — mění se VÝHRADNĚ přes {@code /api/auth/email/change}
 * (OTP na novou adresu), tahle hodnota je jen ke čtení.
 */
public record Profile(
    String firstName,
    String lastName,
    String phone,
    String contactEmail,
    String loginEmail,
    ProfileVisibility visibility,
    List<ProfileFieldAudience> visibleFields,
    Photo avatar) {
}
