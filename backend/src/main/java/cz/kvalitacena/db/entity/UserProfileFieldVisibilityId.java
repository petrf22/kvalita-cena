package cz.kvalitacena.db.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Složený klíč pro {@link UserProfileFieldVisibility} — JPA {@code @IdClass} vyžaduje veřejnou
 * třídu s no-arg konstruktorem (stejný vzor jako {@link ExchangeRateId}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileFieldVisibilityId implements Serializable {
  private Long userId;
  private ProfileField field;
  private Audience audience;
}
