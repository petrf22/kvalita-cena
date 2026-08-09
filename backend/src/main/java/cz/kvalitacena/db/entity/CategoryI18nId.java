package cz.kvalitacena.db.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Složený klíč pro {@link CategoryI18n} — JPA @IdClass vyžaduje veřejnou třídu s no-arg konstruktorem. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CategoryI18nId implements Serializable {
  private Long categoryId;
  private String locale;
}
