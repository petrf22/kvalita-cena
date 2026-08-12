package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Jeden řádek matice "pole × publikum" (auth.user_profile_field_visibility) — EXISTENCE řádku
 * znamená, že dané {@link ProfileField} je pro dané {@link Audience} viditelné, žádný řádek
 * znamená neviditelné. Vynucuje se výhradně v {@code UserProfileService.isFieldVisible} — sama
 * o sobě nic negarantuje, pokud globální {@code user_profile.visibility} zůstává
 * {@code ANONYMOUS} (docs/soukromi.md).
 */
@Entity
@Table(name = "user_profile_field_visibility", schema = "auth")
@IdClass(UserProfileFieldVisibilityId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfileFieldVisibility {

  @Id
  @Column(name = "user_id")
  private Long userId;

  @Id
  @Enumerated(EnumType.STRING)
  @Column(name = "field", length = 20)
  private ProfileField field;

  @Id
  @Enumerated(EnumType.STRING)
  @Column(name = "audience", length = 10)
  private Audience audience;
}
