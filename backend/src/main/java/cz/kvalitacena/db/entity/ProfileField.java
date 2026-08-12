package cz.kvalitacena.db.entity;

/**
 * Pole profilu, pro které lze zvlášť zapnout viditelnost vůči {@link Audience}
 * (auth.user_profile_field_visibility.field). DISPLAY_NAME je tu samostatně vedle
 * FIRST_NAME/LAST_NAME, protože přezdívka (app_user.display_name) je jiný sloupec než jméno
 * a příjmení (user_profile) — uživatel je smí zobrazit nezávisle na sobě.
 */
public enum ProfileField {
  FIRST_NAME,
  LAST_NAME,
  DISPLAY_NAME,
  CONTACT_EMAIL,
  PHONE,
  AVATAR
}
