package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Audience;
import cz.kvalitacena.db.entity.ProfileField;

/**
 * Jeden řádek matice viditelnosti — použitý jako GraphQL výstupní typ ({@code Profile.visibleFields})
 * i jako vstupní typ ({@code UpdateProfileInput.visibleFields}); pole jsou v obou směrech
 * stejná, samostatná Java třída pro input by nic nepřidala.
 */
public record ProfileFieldAudience(ProfileField field, Audience audience) {
}
