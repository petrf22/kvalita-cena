package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Jeden den kurzovního lístku pro jednu měnu vůči CZK (docs/lokalizace.md, "Kurzovní lístek a
 * zobrazovací měna"). CZK samo v tabulce NENÍ — je to pivot, ne stahovaná měna (ČNB kurz
 * CZK/CZK nepublikuje). {@code czkPerUnit} je už normalizované ČNB {@code rate / amount} —
 * ČNB kótuje některé měny po stovkách, ukládat syrový rate by byla tichá stonásobná chyba.
 */
@Entity
@Table(name = "exchange_rate", schema = "fx")
@IdClass(ExchangeRateId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExchangeRate {

  // ČNB validFor — den, pro který kurz PLATÍ, ne den stažení (ten je fetchedAt).
  @Id
  @Column(name = "rate_date")
  private LocalDate rateDate;

  @Id
  @Column(name = "currency", length = 3)
  private String currency;

  @Column(name = "czk_per_unit", nullable = false, precision = 18, scale = 6)
  private BigDecimal czkPerUnit;

  @Column(name = "source", nullable = false, length = 16)
  @Builder.Default
  private String source = "CNB";

  @Column(name = "fetched_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime fetchedAt;
}
