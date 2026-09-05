package cz.kvalitacena.db.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Složený klíč pro {@link ProductName} — JPA @IdClass vyžaduje veřejnou třídu s no-arg konstruktorem. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductNameId implements Serializable {
  private Long productId;
  private String lang;
}
