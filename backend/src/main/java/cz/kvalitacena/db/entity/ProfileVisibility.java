package cz.kvalitacena.db.entity;

/**
 * Globální režim viditelnosti profilu (auth.user_profile.visibility) — výchozí je
 * {@link #ANONYMOUS}, aby si lidé ze setrvačnosti nedávali skutečné jméno (docs/soukromi.md).
 * U {@link #PUBLIC}/{@link #FRIENDS} teprve rozhoduje matice
 * {@link UserProfileFieldVisibility}, KTERÁ pole se pro dané publikum zobrazí.
 */
public enum ProfileVisibility {
  ANONYMOUS,
  PUBLIC,
  FRIENDS
}
