package cz.kvalitacena.db.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/** Složený klíč pro {@link PriceDaily} — JPA @IdClass vyžaduje veřejnou třídu s no-arg konstruktorem. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PriceDailyId implements Serializable {
  private Long productId;
  private Long storeId;
  private PriceKind priceKind;
  private String currency;
  private LocalDate day;
}
