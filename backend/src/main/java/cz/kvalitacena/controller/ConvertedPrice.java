package cz.kvalitacena.controller;

import cz.kvalitacena.service.fx.FxRateService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * GraphQL projekce {@link FxRateService.Converted} (docs/lokalizace.md, "Kurzovní lístek a
 * zobrazovací měna"). {@link #from} vrací {@code null}, přesně jako {@link FxRateService#convert}
 * vrací prázdný {@code Optional} — žádný přepočet neproběhl, klient ukáže původní částku.
 */
public record ConvertedPrice(BigDecimal amount, String currency, LocalDate rateDate) {

  public static ConvertedPrice from(Optional<FxRateService.Converted> converted) {
    return converted.map(c -> new ConvertedPrice(c.amount(), c.currency(), c.rateDate())).orElse(null);
  }
}
