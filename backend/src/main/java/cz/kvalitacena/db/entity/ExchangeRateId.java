package cz.kvalitacena.db.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/** Složený klíč pro {@link ExchangeRate} — JPA @IdClass vyžaduje veřejnou třídu s no-arg konstruktorem. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeRateId implements Serializable {
  private LocalDate rateDate;
  private String currency;
}
